package cleveres.tricky.cleverestech.keystore;

import org.junit.Test;
import java.io.StringReader;
import static org.junit.Assert.assertEquals;

public class XMLParserBugTest {

    @Test
    public void testMixedContent() throws Exception {
        String xml = "<Root><Data>Part1<Inner/>Part2</Data></Root>";

        XMLParser parser = new XMLParser(new StringReader(xml));
        String extracted = parser.obtainPath("Root.Data").get("text");

        assertEquals("Extracted text content mismatch", "Part1Part2", extracted);
    }
}
