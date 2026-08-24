package io.mindspice.magenta.core;

/**
 * Filter criteria for listing conversation sessions.
 *
 * @param favorite    filter by favorite status, null means no filter
 * @param archived    filter by archived status, null means no filter
 * @param origin      filter by origin string, null means no filter
 * @param searchQuery search term matched against title, null means no filter
 * @param limit       max results
 * @param offset      pagination offset
 */
public record SessionFilter(
    @Nullable Boolean favorite,
    @Nullable Boolean archived,
    @Nullable String origin,
    @Nullable String searchQuery,
    int limit,
    int offset
) {
    public static SessionFilter all() {
        return new SessionFilter(null, null, null, null, 50, 0);
    }
}
