package com.aiengineering.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepoConnectRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(strings = {
        "https://github.com/octocat/hello-world",
        "https://github.com/octo-cat/hello.world_2",
        "https://github.com/octocat/hello-world.git"
    })
    void acceptsGithubUrls(String url) {
        assertEquals(0, validator.validate(new RepoConnectRequest(url)).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://github.com/o/r",
        "https://evil.com/o/r",
        "https://github.com.evil.com/o/r",
        "https://evil.com/github.com/o/r",
        "file:///etc/passwd",
        "https://github.com/o/r/",
        "https://github.com/o",
        "https://user@github.com/o/r"
    })
    void rejectsOtherUrls(String url) {
        assertEquals(1, validator.validate(new RepoConnectRequest(url)).size());
    }
}
