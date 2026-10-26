package de.tyro.genshinapp.entity

import jakarta.persistence.*
import java.time.Instant

/** A wiki page identity, separate from the game's item IDs. */
@Entity
@Table(name = "hoyowiki_entry", indexes = [Index(name = "idx_hoyowiki_entry_menu", columnList = "menu_id")])
open class HoyowikiEntry {
    @Id
    @Column(name = "page_id")
    open var pageId: Long = 0

    @Column(name = "menu_id", nullable = false, length = 32)
    open var menuId: String = ""

    @Column(nullable = false, length = 512)
    open var name: String = ""

    @Lob
    @Column(name = "summary_json")
    open var summaryJson: String? = null

    @Lob
    @Column(name = "detail_json")
    open var detailJson: String? = null

    @Column(name = "last_queried_at")
    open var lastQueriedAt: Instant? = null

    @Column(name = "last_detail_queried_at")
    open var lastDetailQueriedAt: Instant? = null

    @Column(name = "source_parser_version")
    open var sourceParserVersion: Int? = null

    @Column(name = "source_parsed_at")
    open var sourceParsedAt: Instant? = null

    @Column(name = "source_last_error", length = 2048)
    open var sourceLastError: String? = null
}

/** Totals describe the last successfully projected full list, never a partial download. */
@Entity
@Table(name = "hoyowiki_catalog_state")
open class HoyowikiCatalogState {
    @Id
    @Column(name = "menu_id", length = 32)
    open var menuId: String = ""

    @Column(name = "entry_count")
    open var entryCount: Int? = null

    @Column(name = "first_page_id")
    open var firstPageId: Long? = null

    @Column(name = "last_checked_at")
    open var lastCheckedAt: Instant? = null

    @Column(name = "last_full_sync_at")
    open var lastFullSyncAt: Instant? = null

    @Column(name = "bundled_version", length = 128)
    open var bundledVersion: String? = null
}

/** The artifact set catalog persists even when a startup probe skips its download. */
@Entity
@Table(name = "game_artifact_set")
open class GameArtifactSet {
    @Id
    @Column(name = "set_key", length = 160)
    open var setKey: String = ""

    @Column(nullable = false, length = 512)
    open var name: String = ""

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hoyowiki_entry_id")
    open var hoyowikiEntry: HoyowikiEntry? = null
}
