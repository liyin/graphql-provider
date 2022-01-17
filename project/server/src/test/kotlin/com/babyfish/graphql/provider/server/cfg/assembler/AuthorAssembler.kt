package com.babyfish.graphql.provider.server.cfg.assembler

import com.babyfish.graphql.provider.server.cfg.Author
import com.babyfish.graphql.provider.server.cfg.Book
import org.babyfish.graphql.provider.server.EntityAssembler
import org.babyfish.graphql.provider.server.cfg.EntityConfiguration

class AuthorAssembler: EntityAssembler<Author> {

    override fun EntityConfiguration<Author>.assemble() {
        id(Author::id)
        mappedList(Author::books, Book::authors)
    }
}