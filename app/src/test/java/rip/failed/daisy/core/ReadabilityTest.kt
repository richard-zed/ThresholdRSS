package rip.failed.daisy.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadabilityTest {

    private fun page(body: String) = "<html><head><title>T</title></head><body>$body</body></html>"

    @Test
    fun `prose split by br inside nested wrappers does not drag in the rails`() {
        // Proportions copied from the real page: the article is ~55% of #main's
        // text and its body div ~92% of the article. Paragraphs are bare text
        // split by <br />, so there is no <p> to score.
        val para = "This is a sentence of prose, with commas, that reads like an article, " +
            "and it runs on for a while so that it scores above the page wrapper."
        val body = page(
            """
            <div id="content-bg"><div id="content"><div id="main-wrap"><div id="main">
              <article class="full">
                <h1>Headline</h1>
                <div class="author">Written by Somebody in Radeon on a date.</div>
                <div class="content">
                  $para<br /><br />$para<br /><br />$para<br /><br />$para
                </div>
              </article>
              <div class="after-article">
                <div class="text">Related News</div>
                <div class="text">Popular News</div>
              </div>
            </div></div></div></div>
            """.trimIndent(),
        )

        val a = Readability.extract(body, "https://example.com/a")
        assertNotNull(a)
        assertTrue("body should be present", a!!.html.contains("runs on for a while"))
        assertFalse("rails must not be body", a.html.contains("Related News"))
        assertFalse("author line must not be body", a.html.contains("Written by"))
    }

    @Test
    fun `a plain p-based article still extracts cleanly`() {
        val p = "A sentence of ordinary article prose, punctuated, and long enough to count."
        val body = page(
            """
            <div id="wrapper"><div id="main">
              <article><h1>Headline</h1>
                <p>$p</p><p>$p</p><p>$p</p>
              </article>
              <aside>Sidebar</aside>
            </div></div>
            """.trimIndent(),
        )

        val a = Readability.extract(body, "https://example.com/b")
        assertNotNull(a)
        assertTrue(a!!.html.contains("ordinary article prose"))
        assertFalse(a.html.contains("Sidebar"))
    }
}
