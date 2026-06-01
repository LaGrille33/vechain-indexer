package org.vechain.indexer.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import org.springframework.data.mongodb.core.convert.MongoCustomConversions
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories
import org.vechain.indexer.xapp.badge.BadgeType

@Configuration
@EnableMongoRepositories(basePackages = ["org.vechain.indexer"])
open class MongoDbConfig {

    @Bean
    open fun mongoTemplate(
        dbFactory: MongoDatabaseFactory,
        converter: MappingMongoConverter,
    ): MongoTemplate {
        converter.customConversions =
            MongoCustomConversions(listOf(BadgeTypeWriteConverter(), BadgeTypeReadConverter()))
        converter.afterPropertiesSet()
        return FilteringMongoTemplate(dbFactory, converter)
    }
}

@WritingConverter
private class BadgeTypeWriteConverter : Converter<BadgeType, String> {
    override fun convert(source: BadgeType): String = source.value
}

@ReadingConverter
private class BadgeTypeReadConverter : Converter<String, BadgeType> {
    override fun convert(source: String): BadgeType = BadgeType.fromValue(source)
}
