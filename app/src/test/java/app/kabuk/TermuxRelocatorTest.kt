package app.kabuk

import app.kabuk.term.Relocator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermuxRelocatorTest {
    @Test fun binaryKeepsLength() {
        val elf = "\u007fELF\u0000/data/data/com.termux/files/usr/lib\u0000x/data/data/com.termux/files/usr/bin/sh\u0000".toByteArray()
        val out = Relocator.rewrite(elf)!!
        assertEquals(elf.size, out.size)
        assertEquals("\u007fELF\u0000/data/data/app.kabuk//files/usr/lib\u0000x/data/data/app.kabuk//files/usr/bin/sh\u0000", String(out))
    }

    @Test fun textGetsCleanPath() {
        val sh = "#!/data/data/com.termux/files/usr/bin/sh\nls /data/data/com.termux/files/home\n".toByteArray()
        assertEquals("#!/data/data/app.kabuk/files/usr/bin/sh\nls /data/data/app.kabuk/files/home\n", String(Relocator.rewrite(sh)!!))
    }

    @Test fun dpkgPathsWithoutLeadingSlash() {
        val sums = "d41d8cd98f00b204e9800998ecf8427e  data/data/com.termux/files/usr/bin/bash\n".toByteArray()
        assertEquals("d41d8cd98f00b204e9800998ecf8427e  data/data/app.kabuk/files/usr/bin/bash\n", String(Relocator.rewrite(sums)!!))
    }

    @Test fun untouchedWhenNoPrefix() {
        assertNull(Relocator.rewrite("com.termux.app.TermuxService\u0000".toByteArray()))
        assertArrayEquals("x".toByteArray(), Relocator.rewrite("x".toByteArray()) ?: "x".toByteArray())
    }

    @Test fun symlinkTargets() {
        assertEquals("/data/data/app.kabuk/files/usr/share/termux-keyring/a.gpg",
            Relocator.rewritePath("/data/data/com.termux/files/usr/share/termux-keyring/a.gpg"))
        assertEquals("../lib/libfoo.so", Relocator.rewritePath("../lib/libfoo.so"))
    }
}
