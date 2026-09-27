package com.npc.mediahandler.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TitleVariantsTest {

    @Test
    void groupPrefixedEpisode() {
        TitleVariants.Parsed p = TitleVariants.parse("jajunge-south.park.s23e02.1080p");
        assertThat(p.titleWords()).containsExactly("jajunge", "south", "park");
        assertThat(p.season()).isEqualTo("S23");
        assertThat(p.episode()).isEqualTo("E02");
        assertThat(TitleVariants.candidates(p))
                .startsWith("jajunge south park", "south park", "jajunge south")
                .doesNotContain("south", "park", "jajunge");
    }

    @Test
    void episodeFolderName() {
        TitleVariants.Parsed p = TitleVariants.parse(
                "South.Park.S23E10.Weihnachtsschnee.German.DL.AC3D.1080p.BluRay.x264-JaJunge");
        assertThat(p.titleWords()).containsExactly("south", "park");
        assertThat(p.isShow()).isTrue();
        assertThat(TitleVariants.candidates(p)).first().isEqualTo("south park");
    }

    @Test
    void seasonPackFolderHasTitleButNoEpisode() {
        TitleVariants.Parsed p = TitleVariants.parse("South.Park.S23.German.DL.AC3D.1080p.BluRay.x264-JaJunge");
        assertThat(p.titleWords()).containsExactly("south", "park");
        assertThat(p.isShow()).isFalse();
    }

    @Test
    void movieWithYear() {
        TitleVariants.Parsed p = TitleVariants.parse("Der.Mann.mit.zwei.Gehirnen.1983.GERMAN.DL.AC3D.1080p.BluRay.x264-iNFOTv");
        assertThat(p.titleWords()).containsExactly("der", "mann", "mit", "zwei", "gehirnen");
        assertThat(p.year()).isEqualTo("1983");
        assertThat(p.isShow()).isFalse();
    }

    @Test
    void leadingYearIsTitle() {
        TitleVariants.Parsed p = TitleVariants.parse("1917.2019.German.DL.1080p.BluRay.x264");
        assertThat(p.titleWords()).containsExactly("1917");
        assertThat(p.year()).isEqualTo("2019");
    }

    @Test
    void alternativeEpisodeMarker() {
        TitleVariants.Parsed p = TitleVariants.parse("show.name.3x07.720p");
        assertThat(p.titleWords()).containsExactly("show", "name");
        assertThat(p.season()).isEqualTo("S03");
        assertThat(p.episode()).isEqualTo("E07");
    }

    @Test
    void twoWordTitleAllowsSingleWords() {
        assertThat(TitleVariants.candidates(TitleVariants.parse("grp-Alien.1979.1080p")))
                .containsExactly("grp alien", "alien", "grp");
    }

    @Test
    void normalizeIgnoresCaseAccentsAndPunctuation() {
        assertThat(TitleVariants.normalize("Amélie")).isEqualTo("amelie");
        assertThat(TitleVariants.normalize("Marvel's Agents of S.H.I.E.L.D.")).isEqualTo("marvel s agents of s h i e l d");
        assertThat(TitleVariants.normalize("Fast & Furious")).isEqualTo("fast and furious");
    }
}
