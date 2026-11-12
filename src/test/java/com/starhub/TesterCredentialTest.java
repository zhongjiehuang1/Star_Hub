package com.starhub;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TesterCredentialTest {
    @TempDir Path folder;
    @Test void privateFilePreservesExactPunctuationAndWhitespace()throws Exception{
        Path file=folder.resolve("credential");String synthetic="  synthetic-$-percent%-\\-quote\"  ";Files.writeString(file,synthetic);
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));assertEquals(synthetic,TesterCredential.read(file));
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r-----"));assertEquals(synthetic,TesterCredential.read(file));
    }
    @Test void publicWritableSymlinkRelativeAndEmptyFilesFailClosed()throws Exception{
        Path file=folder.resolve("credential");Files.writeString(file,"synthetic-credential");Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(Exception.class,()->TesterCredential.read(file));
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));Path link=folder.resolve("link");Files.createSymbolicLink(link,file);assertThrows(Exception.class,()->TesterCredential.read(link));
        assertThrows(Exception.class,()->TesterCredential.read(Path.of("relative-credential")));
        Files.writeString(file,"");assertThrows(Exception.class,()->TesterCredential.read(file));
    }
}
