package cleveres.tricky.cleverestech.keystore;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class XMLParser {

    private final String xml;

    public XMLParser(String xml) {
        this.xml = xml;
    }

    public static class Node {
        public final String name;
        public final Map<String, String> attributes = new HashMap<>();
        public String text;
        public final List<Node> children = new ArrayList<>();

        public Node(String name) {
            this.name = name;
        }

        public Node getChild(String name) {
            for (Node child : children) {
                if (child.name.equals(name)) return child;
            }
            return null;
        }

        public List<Node> getChildren(String name) {
            List<Node> list = new ArrayList<>();
            for (Node child : children) {
                if (child.name.equals(name)) list.add(child);
            }
            return list;
        }
    }

    public Node parse() throws XmlPullParserException, IOException {
        XmlPullParserFactory xmlFactoryObject = XmlPullParserFactory.newInstance();
        XmlPullParser parser = xmlFactoryObject.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new StringReader(xml));

        Node root = null;
        List<Node> stack = new ArrayList<>();

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                Node node = new Node(parser.getName());
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    node.attributes.put(parser.getAttributeName(i), parser.getAttributeValue(i));
                }

                if (root == null) {
                    root = node;
                }

                if (!stack.isEmpty()) {
                    stack.get(stack.size() - 1).children.add(node);
                }
                stack.add(node);
            } else if (eventType == XmlPullParser.END_TAG) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            } else if (eventType == XmlPullParser.TEXT) {
                if (!stack.isEmpty()) {
                    String text = parser.getText();
                    if (text != null) {
                        Node parent = stack.get(stack.size() - 1);
                        if (parent.text == null) parent.text = text;
                        else parent.text += text;
                    }
                }
            }
            eventType = parser.next();
        }
        return root;
    }

    private static class Tag {
        final String name;
        final int index;

        Tag(String name, int index) {
            this.name = name;
            this.index = index;
        }
    }

    public Map<String, String> obtainPath(String path) throws Exception {
        XmlPullParserFactory xmlFactoryObject = XmlPullParserFactory.newInstance();
        XmlPullParser parser = xmlFactoryObject.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new StringReader(xml));

        String[] rawTags = path.split("\\.");
        List<Tag> tags = new ArrayList<>();
        for (String rawTag : rawTags) {
            String[] parts = rawTag.split("\\[");
            String name = parts[0];
            int index = 0;
            if (parts.length > 1) {
                index = Integer.parseInt(parts[1].replace("]", ""));
            }
            tags.add(new Tag(name, index));
        }

        return readData(parser, tags, 0, new HashMap<>());
    }

    private Map<String, String> readData(XmlPullParser parser, List<Tag> tags, int index,
                                         Map<String, Integer> tagCounts) throws IOException, XmlPullParserException {
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();
            Tag currentTag = tags.get(index);

            if (name.equals(currentTag.name)) {
                if (tagCounts.getOrDefault(name, 0) < currentTag.index) {
                    tagCounts.put(name, tagCounts.getOrDefault(name, 0) + 1);
                    return readData(parser, tags, index, tagCounts);
                } else {
                    if (index == tags.size() - 1) {
                        return readAttributes(parser);
                    } else {
                        return readData(parser, tags, index + 1, tagCounts);
                    }
                }
            } else {
                skip(parser);
            }
        }

        throw new XmlPullParserException("Path not found");
    }

    private Map<String, String> readAttributes(XmlPullParser parser) throws IOException, XmlPullParserException {
        Map<String, String> attributes = new HashMap<>();
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            attributes.put(parser.getAttributeName(i), parser.getAttributeValue(i));
        }
        if (parser.next() == XmlPullParser.TEXT) {
            attributes.put("text", parser.getText());
        }
        return attributes;
    }

    private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }
}
