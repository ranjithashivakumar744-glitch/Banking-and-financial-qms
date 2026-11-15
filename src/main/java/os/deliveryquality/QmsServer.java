package os.deliveryquality;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class QmsServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Entity> ENTITIES = entities();
    private final String jdbcUrl;
    private final Path webRoot;
    private final HttpServer server;
    private final ExecutorService executor;
    private final TeamMember activeDeveloper;

    public QmsServer(int port, String jdbcUrl, Path webRoot) throws IOException, SQLException {
        this.jdbcUrl = jdbcUrl;
        this.webRoot = webRoot;
        activeDeveloper = initialize();
        server = HttpServer.create(new InetSocketAddress(port), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/", this::api);
        server.createContext("/", this::staticFile);
    }

    public void start() { server.start(); }
    public void stop(int delay) {
        server.stop(delay);
        executor.shutdown();
        try {
            if(!executor.awaitTermination(2,TimeUnit.SECONDS)) executor.shutdownNow();
        } catch(InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    public int port() { return server.getAddress().getPort(); }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String database = System.getenv().getOrDefault("QMS_DB", "data/qms.db");
        Path web = Path.of(System.getenv().getOrDefault("QMS_WEB", "src/main/resources/web"));
        Path dbPath = Path.of(database);
        if (dbPath.getParent() != null) Files.createDirectories(dbPath.getParent());
        QmsServer app = new QmsServer(port, "jdbc:sqlite:" + dbPath.toAbsolutePath(), web);
        app.start();
        System.out.println("Banking Quality OS listening on http://localhost:" + app.port());
    }

    private TeamMember initialize() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("""
                CREATE TABLE IF NOT EXISTS requirements (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, title TEXT NOT NULL,
                  description TEXT NOT NULL, owner TEXT NOT NULL, status TEXT NOT NULL, risk TEXT NOT NULL,
                  category TEXT NOT NULL, design_ref TEXT NOT NULL DEFAULT '', test_ref TEXT NOT NULL DEFAULT '',
                  defect_ref TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS defects (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, title TEXT NOT NULL,
                  category TEXT NOT NULL, severity TEXT NOT NULL, status TEXT NOT NULL, owner TEXT NOT NULL,
                  requirement_id INTEGER, opened TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',
                  FOREIGN KEY(requirement_id) REFERENCES requirements(id) ON DELETE SET NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS reviews (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, artifact TEXT NOT NULL,
                  reviewer TEXT NOT NULL, status TEXT NOT NULL, due TEXT NOT NULL, notes TEXT NOT NULL DEFAULT ''
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS tests (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, title TEXT NOT NULL,
                  area TEXT NOT NULL, status TEXT NOT NULL, owner TEXT NOT NULL, last_result TEXT NOT NULL,
                  requirement_id INTEGER, FOREIGN KEY(requirement_id) REFERENCES requirements(id) ON DELETE SET NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS capas (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, title TEXT NOT NULL,
                  root_cause TEXT NOT NULL, owner TEXT NOT NULL, due TEXT NOT NULL, status TEXT NOT NULL,
                  effectiveness TEXT NOT NULL DEFAULT 'Pending'
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS release_state (
                  id INTEGER PRIMARY KEY CHECK(id=1), approved_by TEXT NOT NULL DEFAULT '',
                  rollback_ready INTEGER NOT NULL DEFAULT 0, monitoring_ready INTEGER NOT NULL DEFAULT 0
                )""");
            s.execute("INSERT OR IGNORE INTO release_state(id) VALUES(1)");
            s.execute("CREATE TABLE IF NOT EXISTS app_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            seed(c);
            return rotateDeveloper(c);
        }
    }

    private TeamMember rotateDeveloper(Connection c)throws SQLException {
        List<TeamMember> developers=List.of(
            new TeamMember("Basavanna","Software Engineer","BS"),
            new TeamMember("Ranjitha S","Software Engineer","RS"));
        String previous=null;
        try(PreparedStatement p=c.prepareStatement("SELECT value FROM app_state WHERE key='last_active_developer'");
            ResultSet r=p.executeQuery()) {
            if(r.next())previous=r.getString(1);
        }
        String previousName=previous;
        List<TeamMember> eligible=developers.stream().filter(member->!member.name().equals(previousName)).toList();
        TeamMember selected=eligible.get(new Random().nextInt(eligible.size()));
        try(PreparedStatement p=c.prepareStatement("INSERT INTO app_state(key,value) VALUES('last_active_developer',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            p.setString(1,selected.name());
            p.executeUpdate();
        }
        return selected;
    }

    private void seed(Connection c) throws SQLException {
        if (count(c, "requirements") > 0) return;
        String B="Basavanna (Software Engineer)", R="Ranjitha S (Software Engineer)";
        insert(c,"requirements","code,title,description,owner,status,risk,category,design_ref,test_ref,defect_ref",new String[][]{
            {"REQ-001","Validate fund-transfer request","Reject transfers with an invalid account, non-positive or over-precision amount, or unsupported currency; return field-level errors within 2 seconds.","BA Team","Approved","High","Data integrity","SRS §3.1","TC-101",""},
            {"REQ-002","Prevent duplicate fund transfers","A transfer retried with the same idempotency key returns the original result and never posts a second debit or credit.","Technical Architect","Approved","Critical","Business rules","API-04","TC-204",""},
            {"REQ-003","Calculate loan interest and EMI correctly","Interest, EMI and amortization follow the approved product rules with defined rounding; results match reviewed test vectors to 0.01.","BA Team + "+B,"In review","Critical","Business rules","Design §5","TC-307","DF-018"},
            {"REQ-004","Enforce balance and daily transfer limits","A transfer never overdraws an account beyond its approved limit and never exceeds the per-customer daily limit; limits reset at the defined cut-off.","BA Team + "+R,"Approved","Critical","Business rules","Design §7","TC-411","DF-030"},
            {"REQ-005","Keep transaction history complete and ordered","History lists every posted, pending and reversed entry in ledger order and reconciles to the account balance.","Technical Architect + "+R,"In review","High","Financial accuracy","API-12","TC-512","DF-021"},
            {"REQ-006","Send transaction alerts promptly","Customer receives the configured alert within 60 seconds of a committed transaction, without duplicates.","BA Team","Draft","Medium","Efficiency","SRS §4.2","TC-614","DF-024"},
            {"REQ-007","Protect customer financial data","Only authorized roles may view account and loan data; every view, export and change is recorded with actor, time and reason in the audit trail.","Security Lead","Approved","Critical","Security","Design §9","TC-718","DF-027"},
            {"REQ-008","Provide daily reconciliation report","Report lists unmatched ledger entries and transfers and is available by 06:00 UTC.","Product Owner","Approved","Medium","Compliance & audit","SRS §5.1","TC-820",""}});
        insert(c,"defects","code,title,category,severity,status,owner,requirement_id,opened,description",new String[][]{
            {"DF-018","Loan EMI rounding error accumulates over the schedule","Business rules","Critical","Open",B,"3","2026-11-12","Rounding each instalment instead of the final balance leaves the closing balance off by up to 0.05."},
            {"DF-021","Transaction history omits pending reversals","Financial accuracy","High","In progress",R,"5","2026-11-11","Reversed entries are hidden until settlement, so history and balance disagree."},
            {"DF-024","Duplicate alert after worker restart","Reliability","Medium","Open",R,"6","2026-11-13","Alert consumer needs a durable idempotency key."},
            {"DF-027","Audit export omits actor identifier","Compliance & audit","High","Resolved","Security Lead","7","2026-11-09","Actor field now included; verify against the access-control test."},
            {"DF-030","Daily transfer limit not reset at cut-off","Escaped","Medium","Open",B,"4","2026-11-14","Found in UAT: the counter carried over past midnight UTC."}});
        insert(c,"reviews","title,artifact,reviewer,status,due,notes",new String[][]{
            {"SRS stakeholder walkthrough","SRS v1.4","Customer + BA Team","Scheduled","2026-11-18","Confirm business rules, limits, rounding and acceptance examples."},
            {"Business-rule review: loan interest","Rule spec v1.2","Customer + Technical Architect","In review","2026-11-17","Check formulas, rounding, edge cases and worked examples."},
            {"Test readiness: transfers and limits","Test plan","Test Manager","Approved","2026-11-16","Evidence linked for idempotency, limits and reversal paths."},
            {"Data mapping: ledger and history","Mapping v2.1","BA Team + Architect","In review","2026-11-19","Check nullability, currency precision and field ownership."}});
        insert(c,"tests","code,title,area,status,owner,last_result,requirement_id",new String[][]{
            {"TC-101","Transfer validation rejects malformed request","Fund transfer","Passed","Test Manager","Pass","1"},
            {"TC-204","Transfer idempotency under retry","Fund transfer","Passed","Integration QA","Pass","2"},
            {"TC-307","Loan interest and EMI vectors","Loan engine","Running",B,"Fail","3"},
            {"TC-411","Balance and daily-limit boundaries","Accounts","Passed",R,"Pass","4"},
            {"TC-512","History reconciles to balance incl. reversals","Transaction history","Blocked",R,"Blocked","5"},
            {"TC-614","Alert delivery within 60 seconds","Notification","Not run","Test Manager","—","6"},
            {"TC-718","Role access and audit trail","Data access","Passed","Security QA","Pass","7"},
            {"TC-820","Reconciliation report completeness","Reporting","Passed","BA Team","Pass","8"}});
        insert(c,"capas","code,title,root_cause,owner,due,status,effectiveness",new String[][]{
            {"CAPA-006","Reconcile history to ledger on every release","Pending reversals were excluded by an unreviewed query filter; no test compared history with balance.",R,"2026-11-25","In progress","Pending"},
            {"CAPA-008","Make interest rules table-driven with reviewed vectors","Rounding rule was implicit in code and absent from the SRS, so reviews and tests could not catch it.",B,"2026-11-27","Open","Pending"},
            {"CAPA-009","Add actor identity to audit export","Export mapping omitted the actor reference during a refactor.","Security Lead","2026-11-20","Verification","Retest scheduled"}});
    }
    private static void insert(Connection c,String table,String cols,String[][] rows) throws SQLException {
        String marks=String.join(",",Collections.nCopies(cols.split(",").length,"?"));
        try (PreparedStatement p=c.prepareStatement("INSERT INTO "+table+"("+cols+") VALUES("+marks+")")) {
            for(String[] row:rows){for(int i=0;i<row.length;i++)p.setString(i+1,row[i]);p.addBatch();} p.executeBatch();
        }
    }

    private void api(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath().substring("/api/".length());
            String method = x.getRequestMethod();
            if (path.equals("health") && method.equals("GET")) { sendJson(x,200,Map.of("status","ok")); return; }
            if (path.equals("dashboard") && method.equals("GET")) { sendJson(x,200,dashboard()); return; }
            if (path.equals("report") && method.equals("GET")) { sendJson(x,200,report()); return; }
            if (path.equals("release")) { release(x,method); return; }
            String[] parts = path.split("/");
            Entity entity = ENTITIES.get(parts[0]);
            if (entity == null) { sendJson(x,404,Map.of("error","Unknown API resource")); return; }
            if (parts.length == 1 && method.equals("GET")) { sendJson(x,200,list(entity)); return; }
            if (parts.length == 1 && method.equals("POST")) { sendJson(x,201,create(x,entity)); return; }
            if (parts.length == 2 && method.equals("PATCH")) { sendJson(x,200,update(x,entity,parseId(parts[1]))); return; }
            if (parts.length == 2 && method.equals("DELETE")) { delete(entity,parseId(parts[1])); sendJson(x,200,Map.of("deleted",true)); return; }
            sendJson(x,405,Map.of("error","Method not allowed"));
        } catch (IllegalArgumentException ex) {
            sendJson(x,400,Map.of("error",ex.getMessage()));
        } catch (NoSuchElementException ex) {
            sendJson(x,404,Map.of("error",ex.getMessage()));
        } catch (Exception ex) {
            ex.printStackTrace();
            sendJson(x,500,Map.of("error","Request could not be completed"));
        }
    }

    private Map<String,Object> dashboard() throws SQLException {
        try(Connection c=connect()) {
            int req=count(c,"requirements"), tests=count(c,"tests"), passed=countWhere(c,"tests","last_result='Pass'"),
                open=countWhere(c,"defects","status NOT IN ('Resolved','Closed')"), critical=countWhere(c,"defects","severity='Critical' AND status NOT IN ('Resolved','Closed')"),
                reviewCount=countWhere(c,"reviews","status NOT IN ('Approved','Complete')");
            return Map.of("requirements",req,"tests",tests,"passedTests",passed,"passRate",tests==0?0:Math.round(100.0*passed/tests),
                "openDefects",open,"criticalDefects",critical,"pendingReviews",reviewCount,"stages",List.of(
                    Map.of("name","PLAN","state","done","detail","Quality plan approved"),
                    Map.of("name","DEFINE","state","done","detail",req+" requirements baselined"),
                    Map.of("name","EXECUTE","state","active","detail","Integration verification running"),
                    Map.of("name","VERIFY","state","next","detail","Acceptance evidence pending"),
                    Map.of("name","MEASURE","state","next","detail","Metrics refreshed daily"),
                    Map.of("name","REPORT","state","next","detail","Release report draft"),
                    Map.of("name","IMPROVE","state","next","detail","CAPA effectiveness tracked")),
                "activity",List.of(
                    Map.of("time","09:42","title","TC-307 failed","detail","EMI rounding mismatch reproduced","tone","red"),
                    Map.of("time","Yesterday","title","DF-027 resolved","detail","Audit export actor field added","tone","mint"),
                    Map.of("time","Nov 15","title","Loan rule review started","detail","Interest and rounding rules in review","tone","amber")),
                "currentUser",Map.of("name",activeDeveloper.name(),"title",activeDeveloper.title(),"initials",activeDeveloper.initials()));
        }
    }

    private Map<String,Object> report() throws SQLException {
        try(Connection c=connect()) {
            int req=count(c,"requirements"), traced=countWhere(c,"requirements","test_ref <> ''"),
                defects=count(c,"defects"), escaped=countWhere(c,"defects","category='Escaped'"),
                reopened=countWhere(c,"defects","status='Reopened'"), tests=count(c,"tests"), passed=countWhere(c,"tests","last_result='Pass'");
            List<Long> defectAges=new ArrayList<>();
            for(Map<String,Object> defect:rows(c,"SELECT opened FROM defects WHERE status NOT IN ('Resolved','Closed')")) {
                try { defectAges.add(Math.max(0,ChronoUnit.DAYS.between(LocalDate.parse(String.valueOf(defect.get("opened"))),LocalDate.now()))); }
                catch(Exception ignored) { }
            }
            Collections.sort(defectAges);
            double medianAge=defectAges.isEmpty()?0:
                defectAges.size()%2==1?defectAges.get(defectAges.size()/2):
                    (defectAges.get(defectAges.size()/2-1)+defectAges.get(defectAges.size()/2))/2.0;
            return Map.ofEntries(Map.entry("requirements",req),Map.entry("tracedRequirements",traced),Map.entry("coverage",req==0?0:Math.round(100.0*traced/req)),
                Map.entry("escapedDefects",escaped),Map.entry("totalDefects",defects),Map.entry("escapedRate",defects==0?0:Math.round(100.0*escaped/defects)),
                Map.entry("reopened",reopened),Map.entry("tests",tests),Map.entry("passed",passed),Map.entry("testPassRate",tests==0?0:Math.round(100.0*passed/tests)),
                Map.entry("reopenRate",defects==0?0:Math.round(100.0*reopened/defects)),
                Map.entry("medianOpenDefectAgeDays",medianAge),
                Map.entry("firstPassReviewYield",75),Map.entry("firstPassReviewYieldBasis","Illustrative demo estimate; review iteration history is not persisted"),
                Map.entry("interfaceContractFailures",countWhere(c,"defects","category='Interoperability' AND status NOT IN ('Resolved','Closed')")),
                Map.entry("changeImpactCoverage",88),Map.entry("changeImpactCoverageBasis","Illustrative demo estimate; change impact and retest history are not persisted"),
                Map.entry("generatedAt",LocalDate.now().toString()));
        }
    }

    private void release(HttpExchange x, String method) throws Exception {
        if (method.equals("GET")) {
            try(Connection c=connect()) {
                Map<String,Object> state=one(c,"SELECT approved_by,rollback_ready,monitoring_ready FROM release_state WHERE id=1");
                int critical=countWhere(c,"defects","severity='Critical' AND status NOT IN ('Resolved','Closed')");
                int req=count(c,"requirements"), traced=countWhere(c,"requirements","test_ref <> ''"),
                    fullyTraced=countWhere(c,"requirements","test_ref <> '' AND design_ref <> ''");
                int tests=count(c,"tests"), passed=countWhere(c,"tests","last_result='Pass'");
                List<Map<String,Object>> checks=List.of(
                    Map.of("name","No open critical defects","pass",critical==0,"detail",critical+" critical defects remain"),
                    Map.of("name","High-severity defects resolved or dispositioned","pass",countWhere(c,"defects","severity='High' AND status NOT IN ('Resolved','Closed')")==0,"detail",countWhere(c,"defects","severity='High' AND status NOT IN ('Resolved','Closed')")+" high defects unresolved (need authority-approved disposition)"),
                    Map.of("name","Bidirectional requirement traceability","pass",req>0&&fullyTraced==req,"detail",fullyTraced+" / "+req+" requirements linked to design and tests"),
                    Map.of("name","Test pass threshold ≥ 80%","pass",tests>0&&100.0*passed/tests>=80,"detail",passed+" / "+tests+" tests passed"),
                    Map.of("name","Customer / authority approval recorded","pass",!String.valueOf(state.get("approved_by")).isBlank(),"detail",String.valueOf(state.get("approved_by")).isBlank()?"Awaiting approval":state.get("approved_by")+" signed"),
                    Map.of("name","Rollback plan rehearsed","pass",number(state.get("rollback_ready"))==1,"detail",number(state.get("rollback_ready"))==1?"Ready":"Not confirmed"),
                    Map.of("name","Monitoring and alerting verified","pass",number(state.get("monitoring_ready"))==1,"detail",number(state.get("monitoring_ready"))==1?"Ready":"Not confirmed"));
                sendJson(x,200,Map.of("checks",checks,"ready",checks.stream().allMatch(v->Boolean.TRUE.equals(v.get("pass"))),"approval",state));
            } return;
        }
        if (method.equals("PATCH")) {
            Map<String,Object> body=body(x);
            try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE release_state SET approved_by=?,rollback_ready=?,monitoring_ready=? WHERE id=1")) {
                Map<String,Object> current=one(c,"SELECT approved_by,rollback_ready,monitoring_ready FROM release_state WHERE id=1");
                Object approval=body.getOrDefault("approved_by",current.get("approved_by"));
                p.setString(1,String.valueOf(approval));
                p.setInt(2,asBoolean(body.getOrDefault("rollback_ready",current.get("rollback_ready")))?1:0);
                p.setInt(3,asBoolean(body.getOrDefault("monitoring_ready",current.get("monitoring_ready")))?1:0);
                p.executeUpdate();
            }
            sendJson(x,200,Map.of("updated",true)); return;
        }
        sendJson(x,405,Map.of("error","Method not allowed"));
    }

    private List<Map<String,Object>> list(Entity e) throws SQLException {
        try(Connection c=connect()) { return rows(c,"SELECT * FROM "+e.table+" ORDER BY id DESC"); }
    }
    private Map<String,Object> create(HttpExchange x,Entity e) throws Exception {
        Map<String,Object> body=body(x); validateRequired(body,e.required);
        Map<String,Object> fields=allowed(body,e);
        if(fields.isEmpty()) throw new IllegalArgumentException("Provide at least one valid field");
        if(e.table.equals("capas") && "Closed".equals(fields.get("status")))
            requireEffectiveness(fields.get("effectiveness"));
        String cols=String.join(",",fields.keySet()), marks=String.join(",",Collections.nCopies(fields.size(),"?"));
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT INTO "+e.table+"("+cols+") VALUES("+marks+")",Statement.RETURN_GENERATED_KEYS)) {
            bind(p,new ArrayList<>(fields.values()));p.executeUpdate();
            try(ResultSet keys=p.getGeneratedKeys()){if(keys.next())return rowById(c,e,keys.getLong(1));}
            return rowById(c,e,lastId(c));
        } catch(SQLException ex) { if(ex.getMessage()!=null&&ex.getMessage().contains("UNIQUE"))throw new IllegalArgumentException("Code already exists");throw ex; }
    }
    private Map<String,Object> update(HttpExchange x,Entity e,long id) throws Exception {
        Map<String,Object> body=body(x);Map<String,Object> fields=allowed(body,e);
        if(fields.isEmpty())throw new IllegalArgumentException("Provide at least one valid field");
        String set=String.join(",",fields.keySet().stream().map(k->k+"=?").toList());
        try(Connection c=connect()) {
            if(e.table.equals("capas") && "Closed".equals(fields.get("status"))) {
                List<Map<String,Object>> existing=rows(c,"SELECT effectiveness FROM capas WHERE id="+id);
                if(existing.isEmpty())throw new NoSuchElementException("Record not found");
                Object evidence=fields.getOrDefault("effectiveness",existing.get(0).get("effectiveness"));
                requireEffectiveness(evidence);
            }
            try(PreparedStatement p=c.prepareStatement("UPDATE "+e.table+" SET "+set+" WHERE id=?")) {
                List<Object> values=new ArrayList<>(fields.values());values.add(id);bind(p,values);
                if(p.executeUpdate()==0)throw new NoSuchElementException("Record not found");
                return rowById(c,e,id);
            }
        } catch(SQLException ex) { if(ex.getMessage()!=null&&ex.getMessage().contains("UNIQUE"))throw new IllegalArgumentException("Code already exists");throw ex; }
    }
    private static void requireEffectiveness(Object evidence) {
        String value=evidence==null?"":String.valueOf(evidence).trim();
        if(value.isBlank()||value.equalsIgnoreCase("Pending")||value.equalsIgnoreCase("Retest scheduled"))
            throw new IllegalArgumentException("Record independent effectiveness evidence before closing the CAPA");
    }
    private void delete(Entity e,long id) throws SQLException {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("DELETE FROM "+e.table+" WHERE id=?")) {
            p.setLong(1,id);if(p.executeUpdate()==0)throw new NoSuchElementException("Record not found");
        }
    }
    private Map<String,Object> rowById(Connection c,Entity e,long id)throws SQLException {
        List<Map<String,Object>> result=rows(c,"SELECT * FROM "+e.table+" WHERE id="+id);
        if(result.isEmpty())throw new NoSuchElementException("Record not found");return result.get(0);
    }
    private Map<String,Object> body(HttpExchange x)throws IOException {
        try{return JSON.readValue(x.getRequestBody(),new TypeReference<>(){});}
        catch(Exception e){throw new IllegalArgumentException("Request body must be valid JSON");}
    }
    private Map<String,Object> allowed(Map<String,Object> body,Entity e) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:e.fields) if(body.containsKey(key)) {
            Object value=body.get(key);
            if(value==null) result.put(key,null);
            else if(key.endsWith("_id") && value instanceof Number) result.put(key,((Number)value).longValue());
            else result.put(key,String.valueOf(value).trim());
        }
        return result;
    }
    private static void validateRequired(Map<String,Object> body,List<String> required) {
        for(String key:required) if(body.get(key)==null||String.valueOf(body.get(key)).isBlank())
            throw new IllegalArgumentException("Required field: "+key);
    }
    private void staticFile(HttpExchange x)throws IOException {
        String path=x.getRequestURI().getPath();
        if(path.equals("/"))path="/index.html";
        if(path.contains("..")||path.contains("\\")||path.startsWith("/api/")){sendText(x,404,"Not found");return;}
        if(path.startsWith("/docs/")&&(path.endsWith(".docx")||path.endsWith(".xlsx"))) {
            String markdownPath=path.substring(0,path.lastIndexOf('.'))+".md";
            Path markdownFile=webRoot.resolve(markdownPath.substring(1)).normalize();
            if(!markdownFile.startsWith(webRoot.normalize())||!Files.isRegularFile(markdownFile)){sendText(x,404,"Not found");return;}
            String markdown=Files.readString(markdownFile,StandardCharsets.UTF_8);
            byte[] bytes=path.endsWith(".docx")?wordDocument(markdown):excelWorkbook(markdown);
            String extension=path.substring(path.lastIndexOf('.'));
            x.getResponseHeaders().set("Content-Type",extension.equals(".docx")
                ?"application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                :"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+markdownFile.getFileName().toString().replaceFirst("\\.md$","")+extension+"\"");
            x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();return;
        }
        Path file=webRoot.resolve(path.substring(1)).normalize();
        if(!file.startsWith(webRoot.normalize())||!Files.isRegularFile(file)){sendText(x,404,"Not found");return;}
        String type=Files.probeContentType(file);
        if(path.endsWith(".js"))type="text/javascript; charset=utf-8";
        if(path.endsWith(".css"))type="text/css; charset=utf-8";
        if(path.endsWith(".md"))type="text/markdown; charset=utf-8";
        if(type==null)type="application/octet-stream";
        x.getResponseHeaders().set("Content-Type",type);
        if(path.endsWith(".md"))x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+file.getFileName()+"\"");
        byte[] bytes=Files.readAllBytes(file);x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();
    }
    private static byte[] wordDocument(String markdown)throws IOException {
        StringBuilder body=new StringBuilder();
        for(String line:markdown.split("\\R",-1)) {
            String text=line.trim();
            if(text.isEmpty()){body.append("<w:p/>");continue;}
            String style="Normal";
            if(text.startsWith("#")) {
                int level=0;while(level<text.length()&&text.charAt(level)=='#')level++;
                style=level==1?"Title":level==2?"Heading1":"Heading2";
                text=text.substring(level).trim();
            } else if(text.startsWith("- ")||text.startsWith("* ")) {
                text="• "+text.substring(2);
            } else if(text.matches("\\d+\\.\\s+.*")) {
                text="• "+text.replaceFirst("^\\d+\\.\\s+","");
            }
            body.append("<w:p><w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr><w:r><w:t xml:space=\"preserve\">")
                .append(xmlEscape(text)).append("</w:t></w:r></w:p>");
        }
        String document="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            +"<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            +body+"<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr></w:body></w:document>";
        return zip(Map.of(
            "[Content_Types].xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>",
            "_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
            "word/_rels/document.xml.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>",
            "word/document.xml",document,
            "word/styles.xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style><w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr></w:style><w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:rPr><w:b/><w:sz w:val=\"30\"/></w:rPr></w:style><w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:rPr><w:b/><w:sz w:val=\"24\"/></w:rPr></w:style></w:styles>"
        ));
    }
    private static byte[] excelWorkbook(String markdown)throws IOException {
        List<List<String>> rows=new ArrayList<>();
        rows.add(List.of("Section","Type","Content"));
        String section="Document";
        for(String line:markdown.split("\\R")) {
            String text=line.trim();
            if(text.isEmpty())continue;
            String type="Text";
            if(text.startsWith("#")) {
                int level=0;while(level<text.length()&&text.charAt(level)=='#')level++;
                section=text.substring(level).trim();type=level==1?"Title":level==2?"Section":"Subsection";
                rows.add(List.of(section,type,section));continue;
            }
            if(text.startsWith("|")&&text.endsWith("|"))type="Table row";
            else if(text.startsWith("- ")||text.startsWith("* ")||text.matches("\\d+\\.\\s+.*"))type="List item";
            else if(text.startsWith("**")&&text.endsWith("**"))type="Label";
            rows.add(List.of(section,type,text));
        }
        StringBuilder sheet=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for(int i=0;i<rows.size();i++) {
            sheet.append("<row r=\"").append(i+1).append("\">");
            for(int j=0;j<rows.get(i).size();j++) {
                String ref=String.valueOf((char)('A'+j))+(i+1);
                sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"");
                if(i==0)sheet.append(" s=\"1\"");
                sheet.append("><is><t xml:space=\"preserve\">").append(xmlEscape(rows.get(i).get(j))).append("</t></is></c>");
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        return zip(Map.of(
            "[Content_Types].xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>",
            "_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"QMS document\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml",sheet.toString(),
            "xl/styles.xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF19263C\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs></styleSheet>"
        ));
    }
    private static byte[] zip(Map<String,String> entries)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream archive=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            for(Map.Entry<String,String> entry:entries.entrySet()) {
                archive.putNextEntry(new ZipEntry(entry.getKey()));
                archive.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static String xmlEscape(String value) {
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
    }
    private static void sendJson(HttpExchange x,int status,Object data)throws IOException {
        byte[] bytes=JSON.writeValueAsBytes(data);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();
    }
    private static void sendText(HttpExchange x,int status,String text)throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
        x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();
    }
    private Connection connect()throws SQLException {
        Connection c=DriverManager.getConnection(jdbcUrl);
        try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}
        return c;
    }
    private static int count(Connection c,String table)throws SQLException {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){return r.next()?r.getInt(1):0;}
    }
    private static int countWhere(Connection c,String table,String clause)throws SQLException {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table+" WHERE "+clause)){return r.next()?r.getInt(1):0;}
    }
    private static long lastId(Connection c)throws SQLException {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT last_insert_rowid()")){r.next();return r.getLong(1);}
    }
    private static Map<String,Object> one(Connection c,String sql)throws SQLException{return rows(c,sql).get(0);}
    private static List<Map<String,Object>> rows(Connection c,String sql)throws SQLException {
        List<Map<String,Object>> out=new ArrayList<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)) {
            ResultSetMetaData md=r.getMetaData();
            while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=md.getColumnCount();i++)row.put(md.getColumnLabel(i),r.getObject(i));out.add(row);}
        } return out;
    }
    private static void bind(PreparedStatement p,List<?> values)throws SQLException {
        for(int i=0;i<values.size();i++){Object v=values.get(i);if(v==null)p.setNull(i+1,Types.NULL);else if(v instanceof Number)p.setObject(i+1,v);else p.setString(i+1,String.valueOf(v));}
    }
    private static long parseId(String raw) {
        try {long id=Long.parseLong(raw);if(id<1)throw new NumberFormatException();return id;}
        catch(NumberFormatException e){throw new IllegalArgumentException("Record id must be a positive number");}
    }
    private static int number(Object value){return value instanceof Number n?n.intValue():0;}
    private static boolean asBoolean(Object value){return value instanceof Boolean b?b:value instanceof Number n?n.intValue()!=0:Boolean.parseBoolean(String.valueOf(value));}
    private static Map<String,Entity> entities() {
        Map<String,Entity> map=new HashMap<>();
        map.put("requirements",new Entity("requirements",List.of("code","title","description","owner","status","risk","category","design_ref","test_ref","defect_ref"),List.of("code","title","description","owner","status","risk","category")));
        map.put("defects",new Entity("defects",List.of("code","title","category","severity","status","owner","requirement_id","opened","description"),List.of("code","title","category","severity","status","owner","opened")));
        map.put("reviews",new Entity("reviews",List.of("title","artifact","reviewer","status","due","notes"),List.of("title","artifact","reviewer","status","due")));
        map.put("tests",new Entity("tests",List.of("code","title","area","status","owner","last_result","requirement_id"),List.of("code","title","area","status","owner","last_result")));
        map.put("capas",new Entity("capas",List.of("code","title","root_cause","owner","due","status","effectiveness"),List.of("code","title","root_cause","owner","due","status")));
        return Map.copyOf(map);
    }
    private record Entity(String table,List<String> fields,List<String> required) {}
    private record TeamMember(String name,String title,String initials) {}
}
