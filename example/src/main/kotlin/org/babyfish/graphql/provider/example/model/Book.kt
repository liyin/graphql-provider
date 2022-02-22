package org.babyfish.graphql.provider.example.model

import org.babyfish.kimmer.sql.Entity
import java.util.*

interface Book: Entity<UUID> {
    val name: String
    val store: BookStore
    val authors: List<Author>
}