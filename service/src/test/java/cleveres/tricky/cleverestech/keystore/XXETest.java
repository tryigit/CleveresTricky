package cleveres.tricky.cleverestech.keystore;

import org.junit.Test;
import java.io.StringReader;
import static org.junit.Assert.fail;
import org.xmlpull.v1.XmlPullParserException;

public class XXETest {

    @Test
    public void testDTDRejected() throws Exception {
        String xml = "<!DOCTYPE foo [ <!ENTITY x \"Hello\"> ]><root>&x;</root>";

        try {
            XMLParser parser = new XMLParser(new StringReader(xml));

            // Try to access content. If it succeeds and resolves 'x', it's vulnerable.
            try {
                String text = parser.obtainPath("root").get("text");
                if ("Hello".equals(text)) {
                     fail("DTD was processed! Vulnerable to XXE.");
                }
            } catch (Exception ignored) {}

            // It should fail either at construction or when accessing the unresolved entity.
            // If it didn't resolve 'x', it will throw XmlPullParserException("unresolved: &x;")
            // If it rejected DOCTYPE, it will throw XmlPullParserException("docdecl not permitted")

            fail("Should have thrown XmlPullParserException due to DTD declaration or unresolved entity");
        } catch (XmlPullParserException e) {
            // Expected
        } catch (Exception e) {
             if (e.getCause() instanceof XmlPullParserException) {
                 return;
             }
             throw e;
        }
    }
}
