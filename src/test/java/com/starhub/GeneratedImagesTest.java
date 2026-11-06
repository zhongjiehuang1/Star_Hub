package com.starhub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeneratedImagesTest {
    @Test void generatedImagesRespectAccountQuotaIncludingExactBoundary(){
        long quota=WorkspaceServlet.QUOTA;
        assertTrue(GeneratedImages.fitsQuota(quota-1024,1024));
        assertFalse(GeneratedImages.fitsQuota(quota-1024,1025));
        assertFalse(GeneratedImages.fitsQuota(quota,Long.MAX_VALUE));
        assertFalse(GeneratedImages.fitsQuota(-1,1));
    }
    @Test void guestGenerationDoesNotClaimAccountStorage(){
        HttpServletRequest req=mock(HttpServletRequest.class);
        ObjectNode result=ModelGateway.JSON.createObjectNode();result.putArray("media").addObject().put("type","image").put("url","data:image/png;base64,AQID");
        GeneratedImages.save(req,ModelGateway.JSON.createObjectNode(),result);
        assertFalse(result.has("imageSaveFailed"));assertFalse(result.path("media").get(0).has("fileId"));verifyNoInteractions(req);
    }
    @Test void expiredSessionKeepsImageAndReportsFailure(){
        HttpServletRequest req=mock(HttpServletRequest.class);
        ObjectNode result=ModelGateway.JSON.createObjectNode();result.putArray("media").addObject().put("type","image").put("url","data:image/png;base64,AQID");
        GeneratedImages.save(req,ModelGateway.JSON.createObjectNode().put("expectedEmail","test@example.invalid"),result);
        assertTrue(result.path("imageSaveFailed").asBoolean());assertTrue(result.path("warning").asText().contains("未能保存"));assertTrue(result.path("media").get(0).path("url").asText().startsWith("data:image/"));
    }
}
