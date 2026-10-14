package com.aiengineering.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single place that knows what a connectable GitHub repo URL looks like.
 * https + github.com only: the user's GitHub token is sent as credentials on
 * clone, so it must never go to another host.
 *
 * Normalizes away a trailing ".git" so "…/repo" and "…/repo.git" are the same repo.
 */
public record GitHubUrl(String owner, String name) {

    /** For use in @Pattern. Same rule as {@link #parse}. */
    public static final String REGEX = "^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?(?:\\.git)?$";

    private static final Pattern PATTERN =
        Pattern.compile("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?$");

    /** @throws IllegalArgumentException if the URL is not a plain https GitHub repo URL */
    public static GitHubUrl parse(String url) {
        Matcher m = url == null ? null : PATTERN.matcher(url);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException(
                "Only https://github.com/<owner>/<repo> URLs can be connected");
        }
        return new GitHubUrl(m.group(1), m.group(2));
    }

    /** Canonical form, without ".git" - what gets stored and compared. */
    public String url() {
        return "https://github.com/" + owner + "/" + name;
    }
}
