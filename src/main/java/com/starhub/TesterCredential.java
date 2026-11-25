package com.starhub;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Keeps the tester credential in a protected file when a path is configured. */
final class TesterCredential {
    private TesterCredential(){}
    static String password(){
        String configured=System.getenv("STARHUB_TESTER_PASSWORD_FILE");
        if(configured!=null&&!configured.isEmpty()){
            try{return read(Path.of(configured));}catch(Exception unavailable){return null;}
        }
        return System.getenv("STARHUB_TESTER_PASSWORD");
    }
    static String read(Path path)throws Exception{
        if(!path.isAbsolute()||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new java.io.IOException("Protected tester credential unavailable");
        Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
        Set<PosixFilePermission> ownerOnly=Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE);
        Set<PosixFilePermission> groupRead=Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.GROUP_READ);
        String owner=Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).getName();
        if((!permissions.equals(ownerOnly)&&!permissions.equals(groupRead))
                ||(!owner.equals("root")&&!owner.equals(System.getProperty("user.name"))))throw new java.io.IOException("Protected tester credential unavailable");
        // Exact bytes preserve legitimate punctuation and whitespace; file
        // provisioning writes no extra newline. Never log the value or digest.
        byte[] content;try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){content=input.readNBytes(4097);}
        if(content.length<1||content.length>4096)throw new java.io.IOException("Protected tester credential unavailable");
        String result=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(content)).toString();
        if(result.indexOf('\0')>=0)throw new java.io.IOException("Protected tester credential unavailable");
        return result;
    }
}
