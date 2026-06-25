package org.vechain.indexer.xapp.metadata

import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.vechain.indexer.BaseStatefulProcessor
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.IndexingResult
import org.vechain.indexer.checkpoint.CheckpointService
import org.vechain.indexer.config.metrics.ProcessorMetrics
import org.vechain.indexer.xapp.metadata.repository.AppMetadataRepository

@Profile("b3tr", "xapp-metadata")
@Component
open class AppMetadataProcessor(
    repository: AppMetadataRepository,
    mongoTemplate: MongoTemplate,
    private val service: AppMetadataService,
    checkpointService: CheckpointService,
    processorMetrics: ProcessorMetrics,
) :
    BaseStatefulProcessor(
        repository = repository,
        mongoTemplate = mongoTemplate,
        indexerName = IndexerNames.XAPP_METADATA.NAME,
        checkpointService = checkpointService,
        collectionName = IndexerNames.XAPP_METADATA.COLLECTION,
        processorMetrics = processorMetrics,
    ) {
    override suspend fun processEntry(entry: IndexingResult) {
        if (entry.events().isEmpty()) return
        // On a fresh database (empty xapp_metadata), backfill every other eligible app before
        // processing the triggering event, so the collection isn't left with a single app. No-op
        // once the collection is non-empty.
        val (bootUpdated, bootExisting) = service.bootstrapIfEmpty(entry.events())
        if (bootUpdated.isNotEmpty() || bootExisting.isNotEmpty()) {
            service.save(bootUpdated, bootExisting)
        }
        val (updated, existing) = service.processEvents(entry.events())
        if (updated.isNotEmpty() || existing.isNotEmpty()) {
            service.save(updated, existing)
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun rollback(blockNumber: Long) {
        super.rollback(blockNumber)
    }
}
