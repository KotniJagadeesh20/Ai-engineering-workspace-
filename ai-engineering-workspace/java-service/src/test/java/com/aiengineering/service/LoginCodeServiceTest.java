package com.aiengineering.service;

import com.aiengineering.dto.AuthResponse;
import com.aiengineering.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginCodeServiceTest {

    private final LoginCodeService service = new LoginCodeService();
    private final AuthResponse auth = new AuthResponse("a", "r", 900);

    @Test
    void codeIsSingleUse() {
        String code = service.issueCode(auth);
        assertSame(auth, service.consumeCode(code));
        assertThrows(UnauthorizedException.class, () -> service.consumeCode(code));
    }

    @Test
    void unknownCodeRejected() {
        assertThrows(UnauthorizedException.class, () -> service.consumeCode("nope"));
    }
}
