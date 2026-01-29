package cleveres.tricky.cleverestech.keystore;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class XMLParser {

    private static class Element {
        String name;
        Map<String, String> attributes = new HashMap<>();
        String text;
        Map<String, List<Element>> children = new HashMap<>();

        Element(String name) {
            this.name = name;
        }

        void addChild(Element child) {
            children.computeIfAbsent(child.name, k -> new ArrayList<>()).add(child);
        }
    }

    private final Element root;

    public XMLParser(Reader reader) throws Exception {
        root = parse(reader);
    }

    private Element parse(Reader reader) throws Exception {
        XmlPullParserFactory xmlFactoryObject = XmlPullParserFactory.newInstance();
        XmlPullParser parser = xmlFactoryObject.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(reader);

        Element currentElement = null;
        // Stack to keep track of parents
        List<Element> stack = new ArrayList<>();

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    Element element = new Element(parser.getName());
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        element.attributes.put(parser.getAttributeName(i), parser.getAttributeValue(i));
                    }
                    if (!stack.isEmpty()) {
                        stack.get(stack.size() - 1).addChild(element);
                    }
                    stack.add(element);
                    currentElement = element;
                    break;

                case XmlPullParser.TEXT:
                    if (currentElement != null && parser.getText() != null) {
                        String text = parser.getText().trim();
                        if (!text.isEmpty()) {
                            currentElement.text = text;
                        }
                    }
                    break;

                case XmlPullParser.END_TAG:
                    if (!stack.isEmpty()) {
                        Element finished = stack.remove(stack.size() - 1);
                        if (stack.isEmpty()) {
                            return finished;
                        }
                    }
                    currentElement = null;
                    break;
            }
            eventType = parser.next();
        }
        return stack.isEmpty() ? null : stack.get(0);
    }

    public Map<String, String> obtainPath(String path) {
        if (root == null) throw new RuntimeException("XML not parsed");

        String[] rawTags = path.split("\\.");
        Element current = root;

        String firstPart = rawTags[0];
        String rootName = firstPart.split("\\[")[0];

        if (!root.name.equals(rootName)) {
             throw new RuntimeException("Path root mismatch: " + rootName + " vs " + root.name);
        }

        for (int i = 1; i < rawTags.length; i++) {
            String rawTag = rawTags[i];
            String[] parts = rawTag.split("\\[");
            String name = parts[0];
            int index = 0;
            if (parts.length > 1) {
                index = Integer.parseInt(parts[1].replace("]", ""));
            }

            List<Element> children = current.children.get(name);
            if (children == null || index >= children.size()) {
                 throw new RuntimeException("Path not found: " + path);
            }
            current = children.get(index);
        }

        Map<String, String> result = new HashMap<>(current.attributes);
        if (current.text != null) {
            result.put("text", current.text);
        }
        return result;
    }
}
