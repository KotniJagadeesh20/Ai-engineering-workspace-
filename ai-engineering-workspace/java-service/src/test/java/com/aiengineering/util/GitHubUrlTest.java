package com.aiengineering.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitHubUrlTest {

    @Test
    void normalizesGitSuffix() {
        assertEquals("https://github.com/octocat/hello", GitHubUrl.parse("https://github.com/octocat/hello.git").url());
        assertEquals("https://github.com/octocat/hello", GitHubUrl.parse("https://github.com/octocat/hello").url());
    }

    @Test
    void extractsOwnerAndName() {
        GitHubUrl u = GitHubUrl.parse("https://github.com/octo-cat/hello.world_2.git");
        assertEquals("octo-cat", u.owner());
        assertEquals("hello.world_2", u.name());
    }

    @Test
    void rejectsOtherHostsAndShapes() {
        for (String bad : new String[] {
            null, "", "http://github.com/o/r", "https://evil.com/o/r",
            "https://github.com.evil.com/o/r", "https://github.com/o/r/", "https://github.com/o"}) {
            assertThrows(IllegalArgumentException.class, () -> GitHubUrl.parse(bad), String.valueOf(bad));
        }
    }
}
