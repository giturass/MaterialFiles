/*
 *  Modified for Material Files (2026); see third_party/apktool-android/README.md.
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.xml;

import brut.common.Log;
import brut.util.OS;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Iterator;

public final class XmlUtils {
    private static final String TAG = "";

    public static final String XML_PROLOG = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";
    public static final String XML_PREFIX = "xml";
    public static final String XML_URI = "http://www.w3.org/XML/1998/namespace";
    public static final String XMLNS_PREFIX = "xmlns";
    public static final String XMLNS_URI = "http://www.w3.org/2000/xmlns/";

    private static final String FEATURE_DISALLOW_DOCTYPE_DECL =
        "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String FEATURE_LOAD_EXTERNAL_DTD =
        "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private XmlUtils() {
        // Private constructor for utility class.
    }

    private static DocumentBuilder newDocumentBuilder(boolean nsAware)
            throws SAXException, ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(nsAware);
        factory.setExpandEntityReferences(false);
        // Android's DOM implementation does not implement the Xerces feature
        // names. DOCTYPE rejection below is mandatory on every platform;
        // these optional features provide additional protection on the JVM.
        setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(factory, FEATURE_DISALLOW_DOCTYPE_DECL, true);
        setFeatureIfSupported(factory, FEATURE_LOAD_EXTERNAL_DTD, false);

        try {
            // Standard JAXP 1.5 property names; the constants themselves are
            // absent from Android's javax.xml.XMLConstants.
            factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
            factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
        } catch (IllegalArgumentException ignored) {
            Log.w(TAG, "JAXP 1.5 Support is required to validate XML");
        }

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External XML entities are not supported");
        });
        return builder;
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (ParserConfigurationException ignored) {
            // All XML input passes rejectDoctype before reaching DOM.
        }
    }

    private static void rejectDoctype(InputSource source) throws IOException, SAXException {
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            if (source.getCharacterStream() != null) {
                parser.setInput(source.getCharacterStream());
            } else {
                parser.setInput(source.getByteStream(), source.getEncoding());
            }
            int event;
            do {
                OS.throwIfInterrupted();
                event = parser.nextToken();
                if (event == XmlPullParser.DOCDECL) {
                    throw new SAXException("XML document types are not supported");
                }
            } while (event != XmlPullParser.END_DOCUMENT);
        } catch (XmlPullParserException ex) {
            throw new SAXException("Invalid XML", ex);
        }
    }

    public static Document newDocument() throws SAXException, ParserConfigurationException {
        return newDocument(false);
    }

    public static Document newDocument(boolean nsAware) throws SAXException, ParserConfigurationException {
        return newDocumentBuilder(nsAware).newDocument();
    }

    public static Document parseDocument(String xml) throws IOException, SAXException, ParserConfigurationException {
        return parseDocument(xml, false);
    }

    public static Document parseDocument(String xml, boolean nsAware)
            throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilder builder = newDocumentBuilder(nsAware);
        rejectDoctype(new InputSource(new StringReader(xml)));
        StringReader reader = new StringReader(xml);
        return builder.parse(new InputSource(reader));
    }

    public static Document loadDocument(File file) throws IOException, SAXException, ParserConfigurationException {
        return loadDocument(file, false);
    }

    public static Document loadDocument(File file, boolean nsAware)
            throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilder builder = newDocumentBuilder(nsAware);
        // Not using the parse(File) method on purpose, so that we can control when to close it.
        // Somehow parse(File) does not seem to close the file in all cases.
        // Rewind the same file handle after validation, rather than opening
        // the path a second time and potentially reading a different file.
        try (SeekableByteChannel channel = Files.newByteChannel(file.toPath())) {
            InputStream in = Channels.newInputStream(channel);
            rejectDoctype(new InputSource(in));
            channel.position(0);
            return builder.parse(new InputSource(in));
        }
    }

    public static void saveDocument(Document doc, File file)
            throws IOException, SAXException, ParserConfigurationException, TransformerException {
        TransformerFactory factory = TransformerFactory.newInstance();
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

        byte[] xmlDecl = XML_PROLOG.getBytes(StandardCharsets.US_ASCII);
        byte[] newLine = System.lineSeparator().getBytes(StandardCharsets.US_ASCII);

        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            out.write(xmlDecl);
            out.write(newLine);
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            out.write(newLine);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T evaluateXPath(Document doc, String expression, Class<T> returnType)
            throws XPathExpressionException {
        QName type;
        if (returnType == Node.class) {
            type = XPathConstants.NODE;
        } else if (returnType == NodeList.class) {
            type = XPathConstants.NODESET;
        } else if (returnType == String.class) {
            type = XPathConstants.STRING;
        } else if (returnType == Double.class) {
            type = XPathConstants.NUMBER;
        } else if (returnType == Boolean.class) {
            type = XPathConstants.BOOLEAN;
        } else {
            throw new IllegalArgumentException("Unexpected return type: " + returnType.getName());
        }

        XPath xPath = XPathFactory.newInstance().newXPath();
        xPath.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return doc.lookupNamespaceURI(prefix);
            }

            @Override
            public String getPrefix(String namespaceURI) {
                return doc.lookupPrefix(namespaceURI);
            }

            @Override
            public Iterator<String> getPrefixes(String namespaceURI) {
                String prefix = getPrefix(namespaceURI);
                return prefix != null ? Collections.singleton(prefix).iterator() : Collections.emptyIterator();
            }
        });

        return (T) xPath.evaluate(expression, doc, type);
    }
}
