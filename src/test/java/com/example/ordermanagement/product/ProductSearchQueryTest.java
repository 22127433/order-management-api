package com.example.ordermanagement.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ordermanagement.common.BadRequestException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

class ProductSearchQueryTest {
    @Test
    void preservesDefaultsAndNormalizesKeyword() {
        var query = new ProductSearchQuery("  KeyBOARD  ", BigDecimal.ZERO, new BigDecimal("0.00"),
                null, 0, 20, null);
        assertThat(query.keyword()).isEqualTo("KeyBOARD");
        assertThat(query.pageable().getSort()).isEqualTo(Sort.by("id"));
        assertThat(query.pageable().getPageSize()).isEqualTo(20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "name", "price", "stockQuantity"})
    void supportsWhitelistedSortFieldsWithUniqueTieBreaker(String field) {
        var query = new ProductSearchQuery(null, null, null, null, 0, 100, field + ",DESC");
        Sort expected = Sort.by(Sort.Direction.DESC, field);
        if (!field.equals("id")) {
            expected = expected.and(Sort.by("id"));
        }
        assertThat(query.pageable().getSort()).isEqualTo(expected);
    }

    @Test
    void omittedDirectionMeansAscending() {
        assertThat(new ProductSearchQuery(null, null, null, null, 0, 20, "price").pageable().getSort())
                .isEqualTo(Sort.by("price").and(Sort.by("id")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "description", "createdAt", "price,up", "price,", "price,asc,id", "name.length", "id;drop table products"})
    void rejectsUnsupportedOrMalformedSort(String sort) {
        assertThatThrownBy(() -> new ProductSearchQuery(null, null, null, null, 0, 20, sort))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,101", "2147483647,100"})
    void rejectsInvalidPageBounds(int page, int size) {
        assertThatThrownBy(() -> new ProductSearchQuery(null, null, null, null, page, size, null))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @CsvSource({"-0.01,10", "0,-0.01", "10.01,10.00"})
    void rejectsNegativeOrReversedPriceBounds(BigDecimal min, BigDecimal max) {
        assertThatThrownBy(() -> new ProductSearchQuery(null, min, max, null, 0, 20, null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsOverlongKeyword() {
        assertThatThrownBy(() -> new ProductSearchQuery("x".repeat(121), null, null, null, 0, 20, null))
                .isInstanceOf(BadRequestException.class);
    }
}
