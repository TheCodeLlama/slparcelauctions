package com.slparcelauctions.backend.realty.slug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.realty.RealtyGroupRepository;

@ExtendWith(MockitoExtension.class)
class RealtyGroupSlugFactoryTest {

    @Mock RealtyGroupRepository repo;
    @InjectMocks RealtyGroupSlugFactory factory;

    // No @BeforeEach: pure-fromName tests don't touch the repo. Mockito strict mode
    // would flag any default stubs as unused for those tests.

    @Test
    void basicNameToKebab() {
        assertEquals("mainland-realty-co", factory.fromName("Mainland Realty Co."));
    }

    @Test
    void stripsLeadingTrailingPunctuation() {
        assertEquals("mainland-realty", factory.fromName("!!!Mainland Realty!!!"));
    }

    @Test
    void collapsesConsecutiveSeparators() {
        assertEquals("a-b", factory.fromName("a    b"));
        assertEquals("a-b", factory.fromName("a---b"));
    }

    @Test
    void truncatesLongNameAtBoundary() {
        String name = "the-quick-brown-fox-jumps-over-the-lazy-dog-and-many-many-many-extras";
        String slug = factory.fromName(name);
        assertTrue(slug.length() <= 60, "expected <=60 chars, was " + slug.length());
        assertTrue(slug.endsWith("y") || !slug.endsWith("-"), "no trailing dash");
    }

    @Test
    void emptyOnAllNonAscii() {
        assertEquals("", factory.fromName("你好世界"));
    }

    @Test
    void deriveAppendsSuffixOnCollision() {
        when(repo.countActiveBySlug("mainland")).thenReturn(1L);
        when(repo.countActiveBySlug("mainland-2")).thenReturn(0L);
        assertEquals("mainland-2", factory.derive("Mainland", null));
    }

    @Test
    void deriveSkipsExistingExcludeIdOnRename() {
        // Renaming a row that already owns the target slug should not bump.
        when(repo.countOtherActiveBySlug("mainland", 7L)).thenReturn(0L);
        assertEquals("mainland", factory.derive("Mainland", 7L));
    }

    @Test
    void deriveFallsBackToPlaceholderForEmptyBase() {
        when(repo.countActiveBySlug("group")).thenReturn(0L);
        assertEquals("group", factory.derive("你好", null));
    }

    @Test
    void deriveReturnsBaseWhenNoCollision() {
        when(repo.countActiveBySlug("mainland-realty")).thenReturn(0L);
        assertEquals("mainland-realty", factory.derive("Mainland Realty", null));
    }
}
