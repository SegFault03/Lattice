package com.segfault03.ideadb.dialog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConnectionUrlMaskTest {
    @Test
    void masksQueryParameterPasswords() {
        assertEquals("jdbc:mysql://h/db?user=a&password=****&ssl=true",
                ConnectionDialog.maskUrlSecrets("jdbc:mysql://h/db?user=a&password=hunter2&ssl=true"));
        assertEquals("jdbc:postgresql://h/db?PWD=****", ConnectionDialog.maskUrlSecrets("jdbc:postgresql://h/db?PWD=x"));
    }

    @Test
    void masksUserinfoPasswordAndSemicolonProperties() {
        assertEquals("jdbc:mysql://bob:****@h:3306/db", ConnectionDialog.maskUrlSecrets("jdbc:mysql://bob:s3cret@h:3306/db"));
        assertEquals("jdbc:h2:mem:t;USER=a;PASSWORD=****;MODE=MySQL",
                ConnectionDialog.maskUrlSecrets("jdbc:h2:mem:t;USER=a;PASSWORD=p;MODE=MySQL"));
    }

    @Test
    void leavesPlainUrlsUntouched() {
        assertEquals("jdbc:mysql://h:3306/db", ConnectionDialog.maskUrlSecrets("jdbc:mysql://h:3306/db"));
        assertNull(ConnectionDialog.maskUrlSecrets(null));
    }
}
