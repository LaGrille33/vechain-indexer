package org.vechain.indexer.xapp.scoring

import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.vechain.indexer.BaseStatefulProcessor
import org.vechain.indexer.IndexerNames
import org.vechain.indexer.IndexingResult
import org.vechain.indexer.checkpoint.CheckpointService
import org.vechain.indexer.config.metrics.ProcessorMetrics
import org.vechain.indexer.xapp.scoring.repository.ScoringRoundRepository

@Profile("b3tr", "xapp-scoring")
@Component
open class ScoringProcessor(
    repository: ScoringRoundRepository,
    mongoTemplate: MongoTemplate,
    private val service: ScoringService,
    checkpointService: CheckpointService,
    processorMetrics: ProcessorMetrics,
) :
    BaseStatefulProcessor(
        repository = repository,
        mongoTemplate = mongoTemplate,
        indexerName = IndexerNames.XAPP_SCORING_ROUND.NAME,
        checkpointService = checkpointService,
        collectionName = IndexerNames.XAPP_SCORING_ROUND.COLLECTION,
        processorMetrics = processorMetrics,
    ) {
    override suspend fun processEntry(entry: IndexingResult) {
        if (entry.events().isEmpty()) return
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
