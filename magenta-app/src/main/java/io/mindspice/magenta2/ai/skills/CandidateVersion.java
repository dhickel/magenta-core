package io.mindspice.magenta2.ai.skills;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable skill candidate version. Identity is {@code (logicalKey, scopeType,
 * authoritativeRootId, provenance, formatVersion, contentDigest)}. Display names,
 * filenames, and paths are not identity.
 *
 * <p>This record never carries source bodies, descriptions, hints, resource
 * metadata, or model-visible content.</p>
 */
public record CandidateVersion(
    Long id,
    String logicalKey,
    SkillScope scopeType,
    String authoritativeRootId,
    SkillProvenance provenance,
    int formatVersion,
    String contentDigest,
    Instant createdAt
) {
    private static final Pattern LOGICAL_KEY = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?");
    private static final Pattern PLAIN_ID = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._:-]{0,254})");

    public CandidateVersion {
        Objects.requireNonNull(logicalKey, "logicalKey");
        if (!LOGICAL_KEY.matcher(logicalKey).matches() || logicalKey.contains("--")) {
            throw new IllegalArgumentException("logicalKey is not a canonical skill identifier");
        }
        Objects.requireNonNull(scopeType, "scopeType");
        Objects.requireNonNull(authoritativeRootId, "authoritativeRootId");
        validateAuthority(scopeType, authoritativeRootId);
        Objects.requireNonNull(provenance, "provenance");
        if (formatVersion <= 0) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
        Objects.requireNonNull(contentDigest, "contentDigest");
        if (contentDigest.length() != 64 || !contentDigest.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("contentDigest must be 64-char lowercase hex");
        }
        if (createdAt == null && id != null) {
            throw new IllegalArgumentException("createdAt is required for persisted candidates");
        }
    }

    private static void validateAuthority(SkillScope scope, String authority) {
        if (authority.length() > 261 || authority.indexOf('\\') >= 0 || authority.startsWith("/")
                || authority.contains("..") || authority.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("authoritativeRootId is unsafe");
        }
        if (scope == SkillScope.GLOBAL) {
            if (!"global".equals(authority)) throw new IllegalArgumentException("GLOBAL authority must be global");
            return;
        }
        String expectedPrefix = scope == SkillScope.PROJECT ? "project/" : null;
        if (expectedPrefix != null) {
            if (!authority.startsWith(expectedPrefix)
                    || !PLAIN_ID.matcher(authority.substring(expectedPrefix.length())).matches()) {
                throw new IllegalArgumentException("PROJECT authority must be project/<id>");
            }
            return;
        }
        int separator = authority.indexOf('/');
        if (separator < 1 || separator != authority.lastIndexOf('/')) {
            throw new IllegalArgumentException("workspace authority must be kind/key");
        }
        String kind = authority.substring(0, separator);
        String key = authority.substring(separator + 1);
        if (!(kind.equals("agent") || kind.equals("user")) || !PLAIN_ID.matcher(key).matches()) {
            throw new IllegalArgumentException("workspace authority must be agent/<id> or user/<id>");
        }
    }

    /** Identity without database id. */
    public static CandidateVersion of(
        String logicalKey,
        SkillScope scopeType,
        String authoritativeRootId,
        SkillProvenance provenance,
        int formatVersion,
        String contentDigest,
        Instant createdAt
    ) {
        return new CandidateVersion(null, logicalKey, scopeType, authoritativeRootId,
            provenance, formatVersion, contentDigest, createdAt);
    }

    /** Identity key for uniqueness checks (excludes id and createdAt). */
    public String identityKey() {
        return logicalKey + "|" + scopeType.name() + "|" + authoritativeRootId
            + "|" + provenance.name() + "|" + formatVersion + "|" + contentDigest;
    }
}
