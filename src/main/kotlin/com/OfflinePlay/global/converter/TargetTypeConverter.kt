package com.contenido.global.converter

import com.contenido.domain.interaction.entity.TargetType
import com.contenido.global.exception.InvalidTargetTypeException
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

@Component
class TargetTypeConverter : Converter<String, TargetType> {

    override fun convert(source: String): TargetType {
        return TargetType.entries.find { it.pathSegment == source.lowercase() }
            ?: throw InvalidTargetTypeException()
    }
}
