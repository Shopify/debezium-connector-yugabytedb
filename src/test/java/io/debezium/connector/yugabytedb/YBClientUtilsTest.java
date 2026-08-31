package io.debezium.connector.yugabytedb;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link YBClientUtils#literalTableNameFilters(String)}, the logic that decides
 * whether the connector's {@code table.include.list} can be pushed to the YugabyteDB master as
 * server-side substring name filters (the fast path) or whether discovery must fall back to a full
 * namespace enumeration.
 *
 * <p>Returning an empty set means "fall back to the full (namespace-scoped) enumeration", which is
 * always correct because the caller re-applies the authoritative client-side include/exclude
 * filters regardless.
 */
public class YBClientUtilsTest {

    @Test
    public void shouldDeriveTableNameFromSchemaQualifiedLiteral() {
        // The shape infra-central provisions: "<schema>.<table>".
        assertEquals(Set.of("returns_us_east"),
                YBClientUtils.literalTableNameFilters("core.returns_us_east"));
    }

    @Test
    public void shouldDeriveTableNameFromCatalogAndSchemaQualifiedLiteral() {
        assertEquals(Set.of("returns_us_east"),
                YBClientUtils.literalTableNameFilters("globaldb.core.returns_us_east"));
    }

    @Test
    public void shouldDeriveTableNameFromBareTableLiteral() {
        assertEquals(Set.of("shopify_shops"),
                YBClientUtils.literalTableNameFilters("shopify_shops"));
    }

    @Test
    public void shouldDeriveMultipleLiteralTableNames() {
        assertEquals(Set.of("returns_us_east", "gift_cards_us_east"),
                YBClientUtils.literalTableNameFilters("core.returns_us_east,core.gift_cards_us_east"));
    }

    @Test
    public void shouldTrimWhitespaceAroundEntries() {
        assertEquals(Set.of("returns_us_east", "gift_cards_us_east"),
                YBClientUtils.literalTableNameFilters("  core.returns_us_east ,  core.gift_cards_us_east  "));
    }

    @Test
    public void shouldDeduplicateEqualTableNames() {
        // Same table name reached via different schema prefixes still yields a single filter.
        assertEquals(Set.of("shops"),
                YBClientUtils.literalTableNameFilters("core.shops,shop.shops"));
    }

    @Test
    public void shouldAllowDigitsAndUnderscoresInTableNames() {
        assertEquals(Set.of("product_tags_2024"),
                YBClientUtils.literalTableNameFilters("core.product_tags_2024"));
    }

    @Test
    public void shouldFallBackWhenTableNameIsWildcardRegex() {
        assertTrue(YBClientUtils.literalTableNameFilters("core.returns_.*").isEmpty());
        assertTrue(YBClientUtils.literalTableNameFilters(".*").isEmpty());
    }

    @Test
    public void shouldFallBackWhenTableNameContainsRegexMetacharacters() {
        assertTrue(YBClientUtils.literalTableNameFilters("core.(returns|orders)").isEmpty());
        assertTrue(YBClientUtils.literalTableNameFilters("core.returns[0-9]+").isEmpty());
        assertTrue(YBClientUtils.literalTableNameFilters("core.returns$").isEmpty());
    }

    @Test
    public void shouldFallBackWhenAnyEntryIsNonLiteral() {
        // One literal and one regex entry: we cannot scope safely, so fall back for the whole list.
        assertTrue(YBClientUtils.literalTableNameFilters("core.returns_us_east,core.orders_.*").isEmpty());
    }

    @Test
    public void shouldFallBackWhenAnEntryIsEmpty() {
        assertTrue(YBClientUtils.literalTableNameFilters("core.returns_us_east,,core.orders").isEmpty());
    }

    @Test
    public void shouldFallBackForNullOrBlankIncludeList() {
        assertTrue(YBClientUtils.literalTableNameFilters(null).isEmpty());
        assertTrue(YBClientUtils.literalTableNameFilters("").isEmpty());
        assertTrue(YBClientUtils.literalTableNameFilters("   ").isEmpty());
    }
}
