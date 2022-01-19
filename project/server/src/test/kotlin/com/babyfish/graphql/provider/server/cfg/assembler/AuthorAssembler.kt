package com.babyfish.graphql.provider.server.cfg.assembler

import com.babyfish.graphql.provider.server.cfg.Author
import com.babyfish.graphql.provider.server.cfg.Book
import org.babyfish.graphql.provider.server.EntityAssembler
import org.babyfish.graphql.provider.server.dsl.ArgumentType
import org.babyfish.graphql.provider.server.dsl.EntityTypeDSL
import org.babyfish.graphql.provider.server.runtime.ilike

class AuthorAssembler: EntityAssembler<Author> {

    override fun EntityTypeDSL<Author>.assemble() {

        id(Author::id)

        mappedList(Author::books, Book::authors) {

            filter {
                argument(
                    "name",
                    ArgumentType.of(String::class).asNullable()
                ) {
                    where(table[Book::name] ilike  it)
                }
            }

            redis {
                dependsOn(Book::name)
            }
        }
    }
}