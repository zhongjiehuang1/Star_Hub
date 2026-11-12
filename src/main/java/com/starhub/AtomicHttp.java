package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Buffer successful account/workspace replies until the one SQL commit is durably confirmed. */
final class AtomicHttp {
    private static final int REQUEST_LIMIT=6*1024*1024,RESPONSE_LIMIT=20*1024*1024;
    static final int MAX_MUTATION_RECEIPT_BYTES=16*1024;
    private static final int LEGACY_READ_ENVELOPE_BYTES=1024;
    private static final com.fasterxml.jackson.databind.ObjectMapper REPLIES=new com.fasterxml.jackson.databind.ObjectMapper(
        com.fasterxml.jackson.core.JsonFactory.builder().streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
            .maxStringLength(RESPONSE_LIMIT).maxDocumentLength(RESPONSE_LIMIT).build()).build());
    // Maximum encoded CHAR/VARCHAR columns plus fixed binary/integer fields of
    // one request-results row. This is an operation cost, never a safety floor.
    static final int MAX_MUTATION_RECEIPT_ROW_BYTES=MAX_MUTATION_RECEIPT_BYTES+(36+36+120+120)*4+32+8+2;
    @FunctionalInterface interface PhysicalAdmission {void check(Connection c,long bytes)throws Exception;}
    private static final Set<String> WORKSPACE_MUTATIONS=Set.of("upload","deleteFile","trashFile","restoreFile","saveChat","deleteChat");
    private static final Set<String> ACCOUNT_MUTATIONS=Set.of("changePassword","addKey","selectKey","deleteKey");
    private AtomicHttp() { }
    static void run(HttpServletRequest original,HttpServletResponse target,FilterChain chain,FileStorage service)throws IOException,ServletException {
        run(original,target,chain,service.atomic.database,service.config,service::ensureReady,service.atomic::transientCapacity);
    }
    static void run(HttpServletRequest original,HttpServletResponse target,FilterChain chain,AtomicDatabase database,Properties config,Runnable ensureReady,PhysicalAdmission physical)throws IOException,ServletException {
        byte[] body=null;JsonNode input=ModelGateway.JSON.createObjectNode();String path=original.getServletPath(),op=null;HeavyRequestBody memory=null;
        boolean post="POST".equals(original.getMethod()),mutation=false,login=false,loginQuery=false,fileProtocol=false;
        try {
            if(post){
                if(!"web".equals(original.getHeader("X-Starhub-Client"))||!DeploymentConfig.allowsOrigin(original.getHeader("Origin"))
                        ||original.getContentType()==null||!original.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json"))throw new StorageFailure(403,"INVALID_ORIGIN","请求来源无效。");
                memory=HeavyRequestBody.read(original,REQUEST_LIMIT,path);body=memory.bytes;
                try{input=ModelGateway.JSON.readTree(body);}catch(Exception invalid){throw new StorageFailure(400,"INVALID_REQUEST","请求无效。");}
                if(input==null||!input.isObject())throw new StorageFailure(400,"INVALID_REQUEST","请求无效。");
                String action=input.path("action").asText();mutation=("/api/workspace".equals(path)?WORKSPACE_MUTATIONS:ACCOUNT_MUTATIONS).contains(action);
                login="/api/account".equals(path)&&"login".equals(action);
                loginQuery="/api/account".equals(path)&&"loginStatus".equals(action);
                fileProtocol="/api/workspace".equals(path)&&Set.of("reserveUpload","cancelUpload").contains(action);
                if(mutation||login||fileProtocol)op=FileStorage.operationId(input.path("operationId").asText());
            }
            if(op==null)op=UUID.randomUUID().toString();
            if(post&&"/api/workspace".equals(path)&&Set.of("upload","cancelUpload","deleteFile").contains(input.path("action").asText())){
                // Content/cleanup consists of individually confirmed bounded TXs.
                // Never wrap the whole logical operation in another SQL scope.
                DeferredRequest request=new DeferredRequest(original,body);BufferedResponse buffered=new BufferedResponse(target);AtomicDatabase.clearConfirmation();
                chain.doFilter(request,buffered);buffered.finishWriter();
                if(spaceRefusal(buffered)){storageRefusal(target,op);return;}
                if(legacyRefusal(buffered)){failure(target,503,"LEGACY_CAPACITY_MIGRATION_REQUIRED","历史文件尚未通过当前分段容量模型验证；原文件和配额保留，需要先受控迁移。",op);return;}
                if(buffered.getStatus()>=500){failure(target,503,"OPERATION_PENDING","服务器故障，请等待恢复",op);return;}
                if(buffered.getStatus()<400){
                    AtomicDatabase.Confirmation proof=AtomicDatabase.confirmation();if(proof==null||!op.equals(proof.operation()))throw StorageFailure.pending();
                    JsonNode result=ModelGateway.JSON.readTree(buffered.bytes.toByteArray());if(!op.equals(result.path("operationId").asText())||!(mutation?"COMMITTED".equals(result.path("state").asText()):Set.of("CANCELLED","COMMITTED").contains(result.path("state").asText())))throw StorageFailure.pending();
                    if(mutation)buffered.identify(op);buffered.setHeader("X-Four-Project-Durability",proof.lease().mode());request.apply();
                }
                buffered.forward();return;
            }
            for(int attempt=0;attempt<3;attempt++){
                DeferredRequest request=new DeferredRequest(original,body);BufferedResponse buffered=new BufferedResponse(target);
                try(AtomicDatabase.Scope scope=database.begin(op)){
                    long user=0;String generation=null;byte[] hash=null;
                    if(mutation){
                        HttpSession session=request.getSession(false);
                        if(!AccountServlet.activeSession(session))throw new StorageFailure(401,"AUTHENTICATION_REQUIRED","请先登录。");
                        if(!Objects.equals(session.getAttribute("starhub.account.email"),input.path("expectedEmail").asText()))throw new StorageFailure(409,"ACCOUNT_CHANGED","账户已在另一个标签页更改，请刷新后重试。");
                        user=(Long)session.getAttribute("starhub.account.id");generation=(String)session.getAttribute("starhub.account.generation");
                        hash=requestHash(config,path,user,generation,input);
                        if(replay(scope.connection,buffered,op,user,generation,path,hash)){
                            ensureReady.run();scope.commit();buffered.setHeader("X-Four-Project-Durability",scope.lease.mode());buffered.forward();return;
                        }
                    }
                    if(mutation)physical.check(scope.connection,WorkspaceMutationBudget.estimate(scope.connection,user,path,input));
                    chain.doFilter(request,buffered);buffered.finishWriter();
                    if((mutation||fileProtocol)&&spaceRefusal(buffered)){storageRefusal(target,op);return;}
                    if(mutation&&legacyRefusal(buffered)){failure(target,503,"LEGACY_CAPACITY_MIGRATION_REQUIRED","历史文件尚未通过当前分段容量模型验证；原文件和配额保留，需要先受控迁移。",op);return;}
                    boolean loginOutcome=login&&(buffered.getStatus()==200||buffered.getStatus()==401||buffered.getStatus()==429);
                    boolean queryOutcome=loginQuery&&(buffered.getStatus()==200||buffered.getStatus()==404||buffered.getStatus()==429);
                    boolean passwordOutcome=mutation&&"/api/account".equals(path)&&"changePassword".equals(input.path("action").asText())&&Set.of(200,400,403,429).contains(buffered.getStatus());
                    if(buffered.getStatus()>=400&&!loginOutcome&&!queryOutcome&&!passwordOutcome){buffered.forward();return;}
                    if(scope.rollbackOnly)throw StorageFailure.pending();
                    if(mutation){
                        buffered.identify(op);if(buffered.bytes.size()>MAX_MUTATION_RECEIPT_BYTES)throw new SQLException("Mutation receipt exceeds its physical model bound");save(scope.connection,buffered,op,user,generation,path,hash);
                    }
                    if(loginOutcome)buffered.identify(op);
                    ensureReady.run();scope.commit();request.apply();
                    buffered.setHeader("X-Four-Project-Durability",scope.lease.mode());buffered.forward();return;
                }catch(SQLException failure){
                    if(AtomicDatabase.knownRollback(failure)&&attempt<2)continue;
                    failure(target,503,mutation||login||fileProtocol?"OPERATION_PENDING":"CLUSTER_UNAVAILABLE","服务器故障，请等待恢复",mutation||login||fileProtocol?op:null);return;
                }
            }
        }catch(StorageFailure failure){if(failure.status==429)target.setHeader("Retry-After","1");failure(target,failure.status,failure.code,failure.getMessage(),mutation||login||fileProtocol?op:null);}
        catch(Exception unavailable){failure(target,503,mutation||login||fileProtocol?"OPERATION_PENDING":"CLUSTER_UNAVAILABLE","服务器故障，请等待恢复",mutation||login||fileProtocol?op:null);}
        finally{if(memory!=null)memory.close();}
    }
    static ObjectNode result(Connection c,long user,String generation,String operation)throws Exception {
        FileStorage.operationId(operation);
        try(PreparedStatement q=c.prepareStatement("SELECT response_status,response_type,response_body FROM starhub_request_results WHERE operation_id=? AND user_id=? AND user_generation=?")){
            q.setString(1,operation);q.setLong(2,user);q.setString(3,generation);try(ResultSet r=q.executeQuery()){
                if(!r.next())throw new StorageFailure(404,"OPERATION_NOT_FOUND","操作不存在。");
                ObjectNode result=ModelGateway.JSON.createObjectNode().put("operationId",operation).put("state","COMMITTED").put("status",r.getInt(1));
                result.set("result",REPLIES.readTree(r.getBytes(3)));return result;
            }
        }
    }
    private static byte[] requestHash(Properties p,String path,long user,String generation,JsonNode input)throws Exception {
        String secret=p.getProperty("storage.idempotency.secret","");if(secret.length()<32)throw StorageFailure.unavailable();
        ObjectNode body=((ObjectNode)input).deepCopy();body.remove("operationId");
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return mac.doFinal((path+"\n"+user+"\n"+generation+"\n"+canonical(body)).getBytes(StandardCharsets.UTF_8));
    }
    static String canonical(JsonNode value){
        if(value.isObject()){
            TreeSet<String> fields=new TreeSet<>();value.fieldNames().forEachRemaining(fields::add);StringJoiner out=new StringJoiner(",","{","}");
            for(String name:fields)out.add(ModelGateway.JSON.getNodeFactory().textNode(name).toString()+":"+canonical(value.get(name)));return out.toString();
        }
        if(value.isArray()){StringJoiner out=new StringJoiner(",","[","]");value.forEach(item->out.add(canonical(item)));return out.toString();}
        return value.toString();
    }
    private static boolean replay(Connection c,BufferedResponse response,String op,long user,String generation,String path,byte[] hash)throws Exception {
        try(PreparedStatement q=c.prepareStatement("SELECT user_id,user_generation,endpoint,request_hash,response_status,response_type,response_body FROM starhub_request_results WHERE operation_id=?")){
            q.setString(1,op);try(ResultSet r=q.executeQuery()){
                if(!r.next())return false;
                if(user!=r.getLong(1)||!Objects.equals(generation,r.getString(2))||!path.equals(r.getString(3))||!MessageDigest.isEqual(hash,r.getBytes(4)))
                    throw new StorageFailure(409,"OPERATION_CONFLICT","操作标识已用于其他请求或旧账户。");
                response.setStatus(r.getInt(5));response.setContentType(r.getString(6));response.setHeader("Cache-Control","no-store");response.bytes.write(r.getBytes(7));return true;
            }
        }
    }
    private static void save(Connection c,BufferedResponse response,String op,long user,String generation,String path,byte[] hash)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("INSERT INTO starhub_request_results(operation_id,user_id,user_generation,endpoint,request_hash,response_status,response_type,response_body) VALUES(?,?,?,?,?,?,?,?)")){
            q.setString(1,op);q.setLong(2,user);q.setString(3,generation);q.setString(4,path);q.setBytes(5,hash);q.setInt(6,response.getStatus());q.setString(7,response.getContentType()==null?"application/json;charset=UTF-8":response.getContentType());q.setBytes(8,response.bytes.toByteArray());q.executeUpdate();
        }
    }
    private static boolean spaceRefusal(BufferedResponse response)throws IOException {
        if(response.getStatus()!=507)return false;JsonNode body=ModelGateway.JSON.readTree(response.bytes.toByteArray());
        return body!=null&&body.isObject()&&"INSUFFICIENT_STORAGE".equals(body.path("code").asText());
    }
    private static boolean legacyRefusal(BufferedResponse response)throws IOException {
        if(response.getStatus()!=503)return false;JsonNode body=ModelGateway.JSON.readTree(response.bytes.toByteArray());
        return body!=null&&body.isObject()&&"LEGACY_CAPACITY_MIGRATION_REQUIRED".equals(body.path("code").asText());
    }
    private static void storageRefusal(HttpServletResponse response,String operation)throws IOException {
        // Earlier chunk commits may already exist. This is a capacity refusal,
        // not proof that the logical operation rolled back or can use a new ID.
        failure(response,507,"INSUFFICIENT_STORAGE","服务器存储空间不足；请保留原操作编号查询结果或取消待完成上传。已提交的分段及预留不会因本次拒绝自动删除。",operation);
    }
    private static void failure(HttpServletResponse response,int status,String code,String message,String op)throws IOException {
        if(response.isCommitted())throw new IOException("Atomic reply was committed before confirmation");
        response.resetBuffer();response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        ObjectNode body=ModelGateway.JSON.createObjectNode().put("code",code).put("error",message);
        if(op!=null)body.put("operationId",op).put("operationState","UNKNOWN");ModelGateway.JSON.writeValue(response.getOutputStream(),body);
    }

    static final class BufferedResponse extends HttpServletResponseWrapper {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private final Map<String,List<String>> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final List<Cookie> cookies=new ArrayList<>();private int status=200;private String type,encoding="UTF-8";private PrintWriter writer;private boolean streamSelected;
        BufferedResponse(HttpServletResponse target){super(target);}
        public int getStatus(){return status;}public void setStatus(int status){this.status=status;}
        public String getContentType(){return type;}public void setContentType(String type){this.type=type;}
        public String getCharacterEncoding(){return encoding;}public void setCharacterEncoding(String encoding){if(!"UTF-8".equalsIgnoreCase(encoding))throw new IllegalArgumentException("UTF-8 required");this.encoding="UTF-8";}
        public void setHeader(String name,String value){headers.put(name,new ArrayList<>(List.of(value)));}
        public void addHeader(String name,String value){headers.computeIfAbsent(name,k->new ArrayList<>()).add(value);}
        public boolean containsHeader(String name){return headers.containsKey(name);}public String getHeader(String name){List<String> values=headers.get(name);return values==null?null:values.get(0);}
        public Collection<String> getHeaders(String name){return List.copyOf(headers.getOrDefault(name,List.of()));}public Collection<String> getHeaderNames(){return List.copyOf(headers.keySet());}
        public void addCookie(Cookie cookie){cookies.add(cookie);}public void setContentLength(int length){}public void setContentLengthLong(long length){}
        public void setIntHeader(String name,int value){setHeader(name,Integer.toString(value));}public void addIntHeader(String name,int value){addHeader(name,Integer.toString(value));}
        public void setDateHeader(String name,long value){throw new IllegalStateException("Atomic response date header unsupported");}public void addDateHeader(String name,long value){throw new IllegalStateException("Atomic response date header unsupported");}
        public boolean isCommitted(){return false;}public void flushBuffer(){finishWriter();}
        public void resetBuffer(){bytes.reset();}public void reset(){bytes.reset();headers.clear();cookies.clear();status=200;type=null;}
        public void sendError(int status,String message)throws IOException{setStatus(status);getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));}public void sendError(int status)throws IOException{sendError(status,"");}
        public void sendRedirect(String location){setStatus(302);setHeader("Location",location);}
        public ServletOutputStream getOutputStream(){
            if(writer!=null)throw new IllegalStateException("Writer already selected");streamSelected=true;
            return new ServletOutputStream(){
                public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){throw new IllegalStateException("Async atomic response unsupported");}
                public void write(int value)throws IOException{if(bytes.size()>=RESPONSE_LIMIT+LEGACY_READ_ENVELOPE_BYTES)throw new IOException("Atomic reply too large");bytes.write(value);}
                public void write(byte[] value,int off,int len)throws IOException{if(len>RESPONSE_LIMIT+LEGACY_READ_ENVELOPE_BYTES-bytes.size())throw new IOException("Atomic reply too large");bytes.write(value,off,len);}
            };
        }
        public PrintWriter getWriter(){if(streamSelected)throw new IllegalStateException("Stream already selected");if(writer==null)writer=new PrintWriter(new OutputStreamWriter(new OutputStream(){public void write(int value)throws IOException{if(bytes.size()>=RESPONSE_LIMIT+LEGACY_READ_ENVELOPE_BYTES)throw new IOException("Atomic reply too large");bytes.write(value);}},StandardCharsets.UTF_8));return writer;}
        void finishWriter(){if(writer!=null){writer.flush();if(writer.checkError())throw StorageFailure.unavailable();}}
        void identify(String op)throws IOException {
            finishWriter();JsonNode value=ModelGateway.JSON.readTree(bytes.toByteArray());if(!value.isObject())throw new IOException("Expected atomic JSON response");
            ((ObjectNode)value).put("operationId",op).put("operationState","COMMITTED");bytes.reset();bytes.write(ModelGateway.JSON.writeValueAsBytes(value));
        }
        void forward()throws IOException {
            finishWriter();HttpServletResponse target=(HttpServletResponse)getResponse();target.setStatus(status);if(type!=null)target.setContentType(type);target.setCharacterEncoding(encoding);
            for(var entry:headers.entrySet())for(int i=0;i<entry.getValue().size();i++){if(i==0)target.setHeader(entry.getKey(),entry.getValue().get(i));else target.addHeader(entry.getKey(),entry.getValue().get(i));}
            for(Cookie cookie:cookies)target.addCookie(cookie);target.getOutputStream().write(bytes.toByteArray());
        }
    }
    private static final class DeferredRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;private DeferredSession session;private boolean rotate;
        DeferredRequest(HttpServletRequest request,byte[] bytes){super(request);this.bytes=bytes;}
        public ServletInputStream getInputStream()throws IOException {
            if(bytes==null)return super.getInputStream();ByteArrayInputStream input=new ByteArrayInputStream(bytes);
            return new ServletInputStream(){public int read(){return input.read();}public int read(byte[] b,int o,int n){return input.read(b,o,n);}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new IllegalStateException("Async atomic request unsupported");}};
        }
        public BufferedReader getReader()throws IOException{return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        public HttpSession getSession(boolean create){if(session!=null)return session;HttpSession actual=super.getSession(create);return actual==null?null:(session=new DeferredSession(actual));}
        public HttpSession getSession(){return getSession(true);}public String changeSessionId(){rotate=true;HttpSession value=getSession(false);if(value==null)throw new IllegalStateException("No session");return value.getId();}
        void apply(){if(session!=null)session.apply();if(rotate)super.changeSessionId();}
    }
    private static final class DeferredSession implements HttpSession {
        private final HttpSession target;private final Map<String,Object> changed=new HashMap<>();private final Set<String> removed=new HashSet<>();private Integer interval;private boolean invalid;
        DeferredSession(HttpSession target){this.target=target;}
        public long getCreationTime(){return target.getCreationTime();}public String getId(){return target.getId();}public long getLastAccessedTime(){return target.getLastAccessedTime();}public ServletContext getServletContext(){return target.getServletContext();}
        public void setMaxInactiveInterval(int seconds){interval=seconds;}public int getMaxInactiveInterval(){return interval==null?target.getMaxInactiveInterval():interval;}
        public Object getAttribute(String name){return removed.contains(name)?null:changed.containsKey(name)?changed.get(name):target.getAttribute(name);}
        public Enumeration<String> getAttributeNames(){Set<String> names=new HashSet<>();target.getAttributeNames().asIterator().forEachRemaining(names::add);names.addAll(changed.keySet());names.removeAll(removed);return Collections.enumeration(names);}
        public void setAttribute(String name,Object value){if(value==null){removeAttribute(name);return;}changed.put(name,value);removed.remove(name);}public void removeAttribute(String name){changed.remove(name);removed.add(name);}
        public void invalidate(){invalid=true;}public boolean isNew(){return target.isNew();}
        void apply(){if(invalid){target.invalidate();return;}for(String name:removed)target.removeAttribute(name);changed.forEach(target::setAttribute);if(interval!=null)target.setMaxInactiveInterval(interval);}
    }
}
