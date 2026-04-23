package me.sgly.translator

import kotlinx.serialization.SerialInfo

@SerialInfo
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SchemaDesc(val description: String)
