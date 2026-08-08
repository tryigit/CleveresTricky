package cleveres.tricky.cleverestech.keystore;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class XMLParser {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ELEMENTS = 4096;
    private static final int MAX_ATTRIBUTES_PER_ELEMENT = 32;
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 4096;
    private static final int MAX_TEXT_CHARS = 12 * 1024 * 1024;
    private static final int MAX_DOCUMENT_CHARS = 16 * 1024 * 1024;

    public static class Element {
        public String name;
        public Map<String, String> attributes = new HashMap<>();
        public StringBuilder textBuilder;
        public Map<String, List<Element>> children = new HashMap<>();

        public Element(String name) {
            this.name = name;
        }

        public void addChild(Element child) {
            children.computeIfAbsent(child.name, k -> new ArrayList<>()).add(child);
        }

        public String getText() {
            return textBuilder == null ? null : textBuilder.toString();
        }

        public Element getChild(String name) {
            List<Element> list = children.get(name);
            return (list != null && !list.isEmpty()) ? list.get(0) : null;
        }

        public List<Element> getChildren(String name) {
            return children.getOrDefault(name, Collections.emptyList());
        }
    }

    private final Element root;

    public XMLParser(Reader reader) throws Exception {
        root = parse(reader);
    }

    public Element getRoot() {
        return root;
    }

    private Element parse(Reader reader) throws Exception {
        String document = readBounded(reader);
        if (document.contains("<!DOCTYPE") || document.contains("<!ENTITY")) {
            throw dtdRejected();
        }

        XmlPullParserFactory xmlFactoryObject = XmlPullParserFactory.newInstance();
        XmlPullParser parser = xmlFactoryObject.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        } catch (Exception ignored) {}
        try {
            parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
            parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {}
        try {
            parser.setFeature(XmlPullParser.FEATURE_VALIDATION, false);
        } catch (Exception ignored) {}
        parser.setInput(new StringReader(document));

        Element currentElement = null;
        // Stack to keep track of parents
        List<Element> stack = new ArrayList<>();

        int eventType = parser.getEventType();
        int elementCount = 0;
        int textChars = 0;
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.DOCDECL:
                    // Security: Explicitly reject DTDs to prevent XXE (XML External Entity) attacks.
                    // Even if FEATURE_PROCESS_DOCDECL was disabled, checking the event type adds
                    // a second layer of defense.
                    throw dtdRejected();

                case XmlPullParser.START_TAG:
                    if (++elementCount > MAX_ELEMENTS || stack.size() >= MAX_DEPTH) {
                        throw new SecurityException("XML structure exceeds safety limits");
                    }
                    String elementName = parser.getName();
                    if (elementName == null || elementName.length() > MAX_NAME_LENGTH) {
                        throw new SecurityException("Invalid XML element name");
                    }
                    int attributeCount = parser.getAttributeCount();
                    if (attributeCount > MAX_ATTRIBUTES_PER_ELEMENT) {
                        throw new SecurityException("Too many XML attributes");
                    }
                    Element element = new Element(elementName);
                    for (int i = 0; i < attributeCount; i++) {
                        String attributeName = parser.getAttributeName(i);
                        String attributeValue = parser.getAttributeValue(i);
                        if (attributeName == null || attributeName.length() > MAX_NAME_LENGTH ||
                                attributeValue == null || attributeValue.length() > MAX_ATTRIBUTE_VALUE_LENGTH) {
                            throw new SecurityException("XML attribute exceeds safety limits");
                        }
                        element.attributes.put(attributeName, attributeValue);
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
                            textChars = Math.addExact(textChars, text.length());
                            if (textChars > MAX_TEXT_CHARS) {
                                throw new SecurityException("XML text exceeds safety limit");
                            }
                            if (currentElement.textBuilder == null) {
                                currentElement.textBuilder = new StringBuilder(text);
                            } else {
                                currentElement.textBuilder.append(text);
                            }
                        }
                    }
                    break;

                case XmlPullParser.ENTITY_REF:
                    throw new SecurityException("XML entities are not allowed");

                case XmlPullParser.END_TAG:
                    if (!stack.isEmpty()) {
                        Element finished = stack.remove(stack.size() - 1);
                        if (stack.isEmpty()) {
                            return finished;
                        }
                        currentElement = stack.get(stack.size() - 1);
                    }
                    break;
            }
            eventType = parser.next();
        }
        return stack.isEmpty() ? null : stack.get(0);
    }

    private static String readBounded(Reader reader) throws IOException {
        char[] buffer = new char[8192];
        StringBuilder document = new StringBuilder();
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (count == 0) continue;
            if (document.length() > MAX_DOCUMENT_CHARS - count) {
                throw new SecurityException("XML document exceeds safety limit");
            }
            document.append(buffer, 0, count);
        }
        return document.toString();
    }

    private static SecurityException dtdRejected() {
        return new SecurityException(
                "DTD is not allowed in this parser to prevent XXE attacks",
                new XmlPullParserException("docdecl not permitted"));
    }

    public Map<String, String> obtainPath(String path) {
        Element current = getElement(path, true);
        Map<String, String> result = new HashMap<>(current.attributes);
        if (current.textBuilder != null) {
            result.put("text", current.textBuilder.toString());
        }
        return result;
    }

    public int getChildCount(String path, String childName) {
        Element element = getElement(path, false);
        if (element == null) return 0;
        List<Element> targetChildren = element.children.get(childName);
        return targetChildren == null ? 0 : targetChildren.size();
    }

    private Element getElement(String path, boolean strict) {
        if (root == null) {
            if (strict) throw new RuntimeException("XML not parsed");
            return null;
        }

        Element current = root;
        int len = path.length();
        int start = 0;

        int dotIndex = path.indexOf('.', start);
        String firstPart = (dotIndex == -1) ? path : path.substring(start, dotIndex);

        int bracketIndex = firstPart.indexOf('[');
        String rootName = (bracketIndex == -1) ? firstPart : firstPart.substring(0, bracketIndex);

        if (!root.name.equals(rootName)) {
            if (strict) throw new RuntimeException("Path root mismatch: " + rootName + " vs " + root.name);
            return null;
        }

        if (dotIndex == -1) return current;

        start = dotIndex + 1;
        while (start < len) {
            dotIndex = path.indexOf('.', start);
            String rawTag = (dotIndex == -1) ? path.substring(start) : path.substring(start, dotIndex);

            bracketIndex = rawTag.indexOf('[');
            String name;
            int index = 0;
            if (bracketIndex != -1) {
                name = rawTag.substring(0, bracketIndex);
                int closeBracket = rawTag.indexOf(']', bracketIndex);
                if (closeBracket != -1) {
                    try {
                        index = Integer.parseInt(rawTag.substring(bracketIndex + 1, closeBracket));
                    } catch (NumberFormatException e) {
                        index = 0; // Fallback to 0 or could throw a RuntimeException
                    }
                }
            } else {
                name = rawTag;
            }

            List<Element> children = current.children.get(name);
            if (children == null || index >= children.size()) {
                if (strict) throw new RuntimeException("Path not found: " + path);
                return null;
            }
            current = children.get(index);

            if (dotIndex == -1) break;
            start = dotIndex + 1;
        }
        return current;
    }
}
