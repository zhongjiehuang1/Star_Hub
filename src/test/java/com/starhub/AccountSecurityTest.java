package com.starhub;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
class AccountSecurityTest {
 @Test void passwordHashUsesSaltAndVaultBindsCiphertextToAccount() throws Exception {
  byte[] salt=new byte[16],other=new byte[16];other[0]=1;
  assertFalse(Arrays.equals(AccountServlet.hash("password",salt),AccountServlet.hash("password",other)));
  byte[] key=new byte[32],nonce=new byte[12],plain="example-key".getBytes(StandardCharsets.UTF_8);
  byte[] encrypted=AccountServlet.crypt(true,plain,key,nonce,"1:openai");
  assertArrayEquals(plain,AccountServlet.crypt(false,encrypted,key,nonce,"1:openai"));
  assertThrows(GeneralSecurityException.class,()->AccountServlet.crypt(false,encrypted,key,nonce,"2:openai"));
 }
}
