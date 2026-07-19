package com.photographer.focusstack.camera.discovery;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Secure namespace-aware parser for Canon's UPnP camera device description. */
public final class CcapiDeviceDescriptionParser {

    public CcapiDiscoveredCamera parse(InputStream xml, URI descriptionUrl) throws IOException {
        Objects.requireNonNull(xml, "xml");
        Objects.requireNonNull(descriptionUrl, "descriptionUrl");
        try {
            DocumentBuilder builder = secureFactory().newDocumentBuilder();
            builder.setErrorHandler(new ThrowingErrorHandler());
            Document document = builder.parse(xml);
            Element device = firstElement(document.getDocumentElement(), "device");
            if (device == null) {
                throw new IOException("CCAPI device description contains no device element");
            }

            Element service = findCcapiService(device);
            if (service == null) {
                throw new IOException("CCAPI device description contains no Canon CCAPI service");
            }
            String accessValue = childText(service, "X_accessURL");
            URI accessUrl = resolveHttpUri(descriptionUrl, accessValue);
            if (accessUrl == null) {
                throw new IOException("CCAPI device description contains no valid X_accessURL");
            }
            if (!sameHost(descriptionUrl, accessUrl)) {
                throw new IOException("CCAPI X_accessURL host differs from device-description host");
            }

            return new CcapiDiscoveredCamera(
                    descriptionUrl,
                    accessUrl,
                    childText(device, "UDN"),
                    childText(device, "friendlyName"),
                    childText(device, "modelName"),
                    childText(device, "serialNumber"),
                    childText(service, "X_deviceNickname"),
                    "1".equals(childText(service, "X_onService"))
            );
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Invalid CCAPI device description", exception);
        }
    }

    private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static Element findCcapiService(Element device) {
        NodeList services = device.getElementsByTagNameNS("*", "service");
        for (int index = 0; index < services.getLength(); index++) {
            Node node = services.item(index);
            if (node instanceof Element candidate) {
                String type = childText(candidate, "serviceType");
                if (CcapiSsdpCodec.isCanonCcapiService(type)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Element firstElement(Element parent, String localName) {
        if (matches(parent, localName)) {
            return parent;
        }
        NodeList descendants = parent.getElementsByTagNameNS("*", localName);
        for (int index = 0; index < descendants.getLength(); index++) {
            Node node = descendants.item(index);
            if (node instanceof Element element) {
                return element;
            }
        }
        return null;
    }

    private static String childText(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element && matches(element, localName)) {
                String value = element.getTextContent();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }

    private static boolean matches(Element element, String localName) {
        String actual = element.getLocalName();
        if (actual == null) {
            actual = element.getTagName();
            int colon = actual.indexOf(':');
            if (colon >= 0) {
                actual = actual.substring(colon + 1);
            }
        }
        return localName.equals(actual);
    }

    private static URI resolveHttpUri(URI base, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI resolved = base.resolve(value.trim());
            if (!resolved.isAbsolute() || resolved.getHost() == null
                    || resolved.getUserInfo() != null || resolved.getFragment() != null) {
                return null;
            }
            String scheme = resolved.getScheme().toLowerCase(Locale.ROOT);
            return "http".equals(scheme) || "https".equals(scheme) ? resolved : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static boolean sameHost(URI first, URI second) {
        String firstHost = normalizeHost(first == null ? null : first.getHost());
        String secondHost = normalizeHost(second == null ? null : second.getHost());
        if (firstHost == null || secondHost == null) {
            return false;
        }
        if (firstHost.equalsIgnoreCase(secondHost)) {
            return true;
        }
        if (!isNumericHost(firstHost) || !isNumericHost(secondHost)) {
            return false;
        }
        try {
            return Arrays.equals(
                    InetAddress.getByName(firstHost).getAddress(),
                    InetAddress.getByName(secondHost).getAddress());
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String value = host;
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int zone = value.indexOf('%');
        return zone >= 0 ? value.substring(0, zone) : value;
    }

    private static boolean isNumericHost(String host) {
        if (host.contains(":")) {
            return host.matches("[0-9A-Fa-f:.]+");
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            try {
                int number = Integer.parseInt(octet);
                if (octet.isEmpty() || number < 0 || number > 255) {
                    return false;
                }
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }

    private static final class ThrowingErrorHandler extends DefaultHandler {
        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }
}
