package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.util.*;

/** Read-only, bounded metadata audit. No recovery decision is inferred from age. */
final class ChunkedRecoveryAudit {
    private static void require(boolean value)throws SQLException {if(!value)throw new SQLException("Chunked recovery metadata is inconsistent");}
    private static String state(Connection c,ChunkedPayloadStore.Plan plan)throws SQLException {
        try(PreparedStatement q=c.prepareStatement("SELECT state FROM starhub_payload_manifests WHERE payload_id=?")){q.setString(1,plan.id());try(ResultSet r=q.executeQuery()){if(!r.next())return null;}}
        return ChunkedPayloadStore.state(c,plan);
    }
    static void verify(Connection c,AtomicPayloads payloads)throws Exception {
        for(int offset=0;;offset+=100){
            int count=0;
            try(PreparedStatement q=c.prepareStatement("SELECT payload_id,operation_id,kind,owner_hash,request_hash,byte_count,chunk_count,sha256,state FROM starhub_payload_manifests ORDER BY payload_id LIMIT 100 OFFSET ?")){
                q.setInt(1,offset);try(ResultSet r=q.executeQuery()){while(r.next()){count++;var plan=new ChunkedPayloadStore.Plan(r.getString(1),r.getString(2),r.getString(3),r.getBytes(4),r.getBytes(5),r.getLong(6),r.getInt(7),r.getBytes(8));String state=r.getString(9);
                    try(PreparedStatement parts=c.prepareStatement("SELECT COUNT(*),COALESCE(SUM(byte_count),0),MIN(chunk_index),MAX(chunk_index) FROM starhub_payload_chunks WHERE payload_id=?")){parts.setString(1,plan.id());try(ResultSet p=parts.executeQuery()){require(p.next());long rows=p.getLong(1),bytes=p.getLong(2);require(rows<=plan.chunkCount()&&bytes<=plan.byteCount());if(state.equals("READY")||state.equals("PUBLISHED"))require(rows==plan.chunkCount()&&bytes==plan.byteCount()&&(rows==0||p.getInt(3)==0&&p.getInt(4)==plan.chunkCount()-1));if(state.equals("CANCELLED"))require(rows==0);}}
                    String parent=switch(plan.kind()){case "FILE"->"SELECT COUNT(*) FROM starhub_file_payloads WHERE file_id=?";case "GUEST"->"SELECT COUNT(*) FROM starhub_guest_payload_refs WHERE payload_id=?";case "PROVIDER_RESULT","PROVIDER_IMAGE"->"SELECT COUNT(*) FROM starhub_provider_payloads WHERE operation_id=?";default->throw new SQLException("Unsupported durable payload kind");};
                    try(PreparedStatement owner=c.prepareStatement(parent)){owner.setString(1,plan.kind().startsWith("PROVIDER_")?plan.operation():plan.id());try(ResultSet p=owner.executeQuery()){require(p.next()&&p.getLong(1)==1);}}
                    if(plan.kind().equals("PROVIDER_IMAGE"))try(PreparedStatement owner=c.prepareStatement("SELECT plan_json FROM starhub_provider_payloads WHERE operation_id=?")){owner.setString(1,plan.operation());try(ResultSet p=owner.executeQuery()){require(p.next());int matching=0;for(var image:ModelGateway.JSON.readTree(p.getString(1)).path("images"))if(AtomicPayloads.json(plan).equals(AtomicPayloads.json(AtomicPayloads.parse(image.path("plan")))))matching++;require(matching==1);}}
                }}
            }
            if(count<100)break;
        }
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM starhub_payload_chunks c LEFT JOIN starhub_payload_manifests m ON m.payload_id=c.payload_id WHERE m.payload_id IS NULL OR c.chunk_index<0 OR c.chunk_index>=m.chunk_count OR c.byte_count<>OCTET_LENGTH(c.content) OR c.byte_count<>CASE WHEN c.chunk_index=m.chunk_count-1 THEN m.byte_count-c.chunk_index*2097152 ELSE 2097152 END")){require(r.next()&&r.getLong(1)==0);}
        for(int offset=0;;offset+=100){
            int count=0;try(PreparedStatement q=c.prepareStatement("SELECT f.file_id,f.operation_id,f.plan_json,f.deleting_op,o.user_id,o.user_generation,o.state FROM starhub_file_payloads f LEFT JOIN file_storage_operations o ON o.op_id=f.operation_id ORDER BY f.file_id LIMIT 100 OFFSET ?")){q.setInt(1,offset);try(ResultSet r=q.executeQuery()){while(r.next()){count++;var plan=AtomicPayloads.parse(ModelGateway.JSON.readTree(r.getString(3)));require(plan.id().equals(r.getString(1))&&plan.operation().equals(r.getString(2))&&r.getString(6)!=null);payloads.owner(plan,r.getLong(5)+":"+r.getString(6),"FILE");String state=state(c,plan),parent=r.getString(7);if(state==null)require(Set.of("RESERVED","CANCELLING","CANCELLED").contains(parent));else if(state.equals("PUBLISHED"))require(parent.equals("COMMITTED")&&r.getString(4)==null);else if(state.equals("STAGING")||state.equals("READY"))require(Set.of("RESERVED","CANCELLING").contains(parent));else require(Set.of("CANCELLING","CANCELLED").contains(parent)||parent.equals("COMMITTED")&&r.getString(4)!=null);}}}if(count<100)break;
        }
        Map<String,Counts> expected=new HashMap<>();
        collect(c,payloads,"starhub_guest_values",false,false,expected);
        collect(c,payloads,"starhub_guest_streams",true,false,expected);
        collect(c,payloads,"starhub_guest_cleanup",false,true,expected);
        for(int offset=0;;offset+=100){
            int count=0;try(PreparedStatement q=c.prepareStatement("SELECT payload_id,guest_id,live_refs,staging_refs,cleanup_refs FROM starhub_guest_payload_refs ORDER BY payload_id LIMIT 100 OFFSET ?")){q.setInt(1,offset);try(ResultSet r=q.executeQuery()){while(r.next()){count++;Counts value=expected.remove(r.getString(1));if(value==null)require(r.getLong(3)==0&&r.getLong(4)==0&&r.getLong(5)==0);else require(value.guest.equals(r.getString(2))&&value.live==r.getLong(3)&&value.stage==r.getLong(4)&&value.cleanup==r.getLong(5));}}}if(count<100)break;
        }require(expected.isEmpty());
        providerPlans(c,payloads);
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM starhub_provider_payloads p LEFT JOIN starhub_provider_operations o ON o.operation_id=p.operation_id WHERE o.operation_id IS NULL OR o.result_cipher IS NOT NULL OR o.state NOT IN ('SENT','UNKNOWN','COMPLETED','COMPLETED_NO_RESULT') OR p.dispatch_hash<>o.dispatch_hash")){require(r.next()&&r.getLong(1)==0);}
    }
    private static void providerPlans(Connection c,AtomicPayloads payloads)throws Exception {
        for(int offset=0;;offset+=100){int count=0;
            try(PreparedStatement q=c.prepareStatement("SELECT p.operation_id,p.dispatch_hash,p.plan_json,p.cipher_binding,p.terminal_state,o.state,o.image_budget_bytes,o.logical_reserved,o.account_user_id,o.account_generation FROM starhub_provider_payloads p JOIN starhub_provider_operations o ON o.operation_id=p.operation_id ORDER BY p.operation_id LIMIT 100 OFFSET ?")){
                q.setInt(1,offset);try(ResultSet r=q.executeQuery()){while(r.next()){count++;String operation=r.getString(1),binding="provider-result:"+operation+":"+HexFormat.of().formatHex(r.getBytes(2));JsonNode json=ModelGateway.JSON.readTree(r.getString(3));var parent=AtomicPayloads.parse(json);
                    require(parent.id().equals(AtomicPayloads.id("provider-result-v1",operation))&&parent.operation().equals(operation)&&parent.kind().equals("PROVIDER_RESULT")&&binding.equals(r.getString(4))&&java.security.MessageDigest.isEqual(parent.requestHash(),payloads.mac("payload-request-v1",binding))&&parent.byteCount()>=28&&parent.byteCount()<=ProviderOperations.RESULT_LIMIT+28L);
                    boolean terminal=Set.of("COMPLETED","COMPLETED_NO_RESULT").contains(r.getString(6));require(!terminal||r.getString(5).equals(r.getString(6))&&r.getLong(8)==0);
                    String parentState=state(c,parent);require(parentState!=null&&(terminal?Set.of("PUBLISHED"):Set.of("STAGING","READY")).contains(parentState));
                    require(json.path("images").size()<=ProviderOperations.MAX_IMAGES);Set<String> images=new HashSet<>();long raw=0;
                    for(var item:json.path("images")){var image=AtomicPayloads.parse(item.path("plan"));String mime=item.path("mime").asText();int index=-1;for(int i=0;i<ProviderOperations.MAX_IMAGES;i++)if(image.id().equals(AtomicPayloads.id("provider-image-v1",operation+":"+i)))index=i;
                        String expected="provider-image:"+operation+":"+HexFormat.of().formatHex(r.getBytes(2))+":"+index+":"+mime;
                        require(index>=0&&images.add(image.id())&&ProviderOperations.IMAGE_TYPES.contains(mime)&&expected.equals(item.path("binding").asText())&&Base64.getDecoder().decode(item.path("plainSha256").asText()).length==32
                                &&image.operation().equals(operation)&&image.kind().equals("PROVIDER_IMAGE")&&image.byteCount()>28&&image.byteCount()<=GeneratedImages.MAX_IMAGE+28L
                                &&java.security.MessageDigest.isEqual(image.ownerHash(),parent.ownerHash())&&java.security.MessageDigest.isEqual(image.requestHash(),payloads.mac("payload-request-v1",expected)));
                        String current=state(c,image);require(current!=null&&(terminal?Set.of("PUBLISHED"):Set.of("STAGING","READY")).contains(current));raw=Math.addExact(raw,image.byteCount()-28);
                    }
                    require(raw<=r.getLong(7));if(!images.isEmpty())require(r.getObject(9)==null&&r.getLong(8)==0); // Account images live only in quota-counted library storage.
                }}
            }if(count<100)break;
        }
        try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT COUNT(*) FROM starhub_provider_operations p WHERE p.logical_reserved<0 OR p.image_budget_bytes<0 OR p.image_budget_bytes>50331648 OR p.logical_reserved>p.image_budget_bytes OR (p.logical_reserved>0 AND (p.account_user_id IS NULL OR p.account_generation IS NULL OR p.state NOT IN ('CLAIMED','SENT','UNKNOWN'))) OR (p.account_user_id IS NOT NULL AND p.image_budget_bytes>0 AND p.logical_reserved=0 AND p.state IN ('CLAIMED','SENT','UNKNOWN') AND NOT EXISTS(SELECT 1 FROM file_storage_operations f WHERE f.op_id=p.operation_id AND f.user_id=p.account_user_id AND f.user_generation=p.account_generation AND f.kind='UPLOAD' AND f.state IN ('RESERVED','COMMITTED','CANCELLING','CANCELLED')))")){require(r.next()&&r.getLong(1)==0);}
    }
    private static final class Counts {final String guest;long live,stage,cleanup;Counts(String guest){this.guest=guest;}}
    private static void collect(Connection c,AtomicPayloads payloads,String table,boolean staging,boolean cleanup,Map<String,Counts> counts)throws Exception {
        String columns=cleanup?"cleanup_id":"tab_id,field_name";
        for(int offset=0;;offset+=100){
            int size=0;String query="SELECT guest_id,"+(cleanup?"legacy_tab":"tab_id")+",refs_json FROM "+table+(cleanup?" WHERE state='CANCELLING'":"")+" ORDER BY guest_id,"+columns+" LIMIT 100 OFFSET ?";
            try(PreparedStatement q=c.prepareStatement(query)){q.setInt(1,offset);try(ResultSet r=q.executeQuery()){while(r.next()){size++;String guest=r.getString(1),tab=r.getString(2);Set<String> seen=new HashSet<>();for(JsonNode ref:ModelGateway.JSON.readTree(r.getString(3))){var plan=AtomicPayloads.parse(ref.path("plan"));payloads.owner(plan,guest+":"+tab,"GUEST");String current=state(c,plan);require(current!=null&&(cleanup?Set.of("PUBLISHED","CANCELLING","CANCELLED"):Set.of("PUBLISHED")).contains(current));if(!seen.add(plan.id()))throw new SQLException("Duplicate guest payload reference");Counts value=counts.computeIfAbsent(plan.id(),ignored->new Counts(guest));require(value.guest.equals(guest));if(cleanup)value.cleanup++;else{value.live++;if(staging)value.stage++;}}}}}if(size<100)break;
        }
    }
    private ChunkedRecoveryAudit(){}
}
