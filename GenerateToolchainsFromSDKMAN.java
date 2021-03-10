///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 9+

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSOutput;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * This class reads or creates the ~/.m2/toolchains.xml file,
 * removes any jdk toolchains marked with 'automaticallyAddedFromSDKMAN',
 * and then runs 'sdk list java' to add the Java JDK's that are available locally.
 * 
 * You can compile and run this class like any Java application, but it is intended to be used with jbang.
 * - https://www.jbang.dev/
 * 
 * Naturally you need SDKMAN installed to provide the JDK's, and it also happens to be able to install jbang.
 * -  sdk install jbang
 * 
 * Run like this:
 * -  jbang GenerateToolchainsFromSDKMAN.java
 * 
 * Or on Linux/OSX just set the executable flag and run the java file directly.
 * 
 * You can also run the latest version directly from github:
 * - jbang https://raw.githubusercontent.com/tbee/mavenToolchains/main/GenerateToolchainsFromSDKMAN.java
 * 
 * Or use alias, autocompletion or any of the other powerful jbang features. Do take a look at jbang's documentation.
 */
public class GenerateToolchainsFromSDKMAN {

    private static final String UTF_8 = "UTF-8";
    private static final String MARKER_TAG = "automaticallyAddedFromSDKMAN";
    
    /**
     * 
     */
    public static void main(String[] args) {
        new GenerateToolchainsFromSDKMAN().run();
    }
    
    /* */
    private void run() {
        try {            
            List<SdkmanJavaVersion> sdkmanJavaVersions = parseSDKMAN();            
            File toolchainsFile = new File(System.getProperty("user.home") + "/.m2/toolchains.xml");
            Document document = parseToolchains(toolchainsFile);            
            removeSDKMANNodes(document);            
            addSDKMANNodes(document, sdkmanJavaVersions);            
            prettyPrint(document, toolchainsFile);            
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /* */
    private List<SdkmanJavaVersion> parseSDKMAN() throws IOException {
        
        // the result
        ArrayList<SdkmanJavaVersion> sdkmanJavaVersions = new ArrayList<>();
        
        // Run SDKMAN
        // TODO: handle different environments and shells
        Process proc = null;
        try {
            // try bash
            String[] command = {"bash", "-c", "source ~/.sdkman/bin/sdkman-init.sh; sdk list java"};
            proc = Runtime.getRuntime().exec(command);
        }
        catch (Exception e) {
            
            // try zsh
            String[] command = {"zsh", "-c", "source ~/.sdkman/bin/sdkman-init.sh; sdk list java"};
            proc = Runtime.getRuntime().exec(command);
        }
        
        // Print out STDERR
        BufferedReader strErr = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
        String e = null;
        while ((e = strErr.readLine()) != null) {
            System.out.println(e);
        }
        
        // Read and print the stdout and extract the info
        boolean unexpectedOutput = false;
        BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        String s = null;
        boolean startMarkerFound = false;
        boolean endMarkerFound = false;
        while ((s = stdInput.readLine()) != null) {
            System.out.println(s);
            
            // Detect if we're in the content part, starting with ---- and ending with ==== 
            endMarkerFound |= startMarkerFound & s.startsWith("=================");            
            boolean skipThisLine = !startMarkerFound || endMarkerFound;
            startMarkerFound |= s.startsWith("----------------");
            if (skipThisLine) {
                continue;
            }
            
            // Split the string into blocks
            String[] strings = s.split("\\|");
            // Make sure the output is what we expect
            if (strings.length != 6) {
                unexpectedOutput = true;
                continue;
            }
            // Find the locally installed JDK's
            if (!"installed".equals(strings[4].trim())) {
                continue;
            }
            
            // Add a found entry
            SdkmanJavaVersion sdkmanJavaVersion = new SdkmanJavaVersion(strings[3].trim(), strings[2].trim(), strings[5].trim());
            sdkmanJavaVersions.add(sdkmanJavaVersion);
        }
        System.out.println("\n");
        if (unexpectedOutput) {
            System.out.println("The output of SDKMAN is not as expected, aborting...");
            System.exit(1);
        }
        for (SdkmanJavaVersion sdkmanJavaVersion : sdkmanJavaVersions) {
            System.out.println("* found " + sdkmanJavaVersion.identifier);
        }        
        return sdkmanJavaVersions; 
    }    
    private class SdkmanJavaVersion {
        final String vendor;
        final String version;
        final String identifier;
        
        SdkmanJavaVersion(String vendor, String version, String identifier) {
            this.vendor = vendor;
            this.version = version; 
            this.identifier = identifier;
        }
    }

    /* */
    private Document parseToolchains(File toolchainsFile) throws ParserConfigurationException, SAXException, IOException {
        
        // Exist or new
        InputStream inputStream;
        if (toolchainsFile.exists()) {
            System.out.println("Reading " + toolchainsFile.getAbsolutePath());
            inputStream = new FileInputStream(toolchainsFile);
        }
        else {
            System.out.println("Toolchains file does not exist, creating " + toolchainsFile.getAbsolutePath());
            inputStream = new ByteArrayInputStream("<toolchains/>".getBytes());
        }

        try {
            // Parse XML
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = dbf.newDocumentBuilder();
            Reader reader = new InputStreamReader(inputStream,UTF_8);
            InputSource inputSource = new InputSource(reader);
            Document document = documentBuilder.parse(inputSource);
            return document;
        }
        finally {
            inputStream.close();
        }
    }
    
    /* */
    private void removeSDKMANNodes(Document document) throws XPathExpressionException {
        XPath xPath = XPathFactory.newInstance().newXPath();
        NodeList nodeList = (NodeList) xPath.compile("//" + MARKER_TAG + "/../..").evaluate(document, XPathConstants.NODESET);
        for (int i = 0; i < nodeList.getLength(); i++) {
            nodeList.item(i).getParentNode().removeChild(nodeList.item(i));
        }
    }

    /* */
    private void addSDKMANNodes(Document document, List<SdkmanJavaVersion> sdkmanJavaVersions) throws XPathExpressionException {
        //        <toolchain>
        //            <type>jdk</type>
        //            <provides>
        //                <version>11</version>
        //            </provides>
        //            <configuration>
        //                <jdkHome>/home/user/.sdkman/candidates/java/11.0.10.hs-adpt</jdkHome>
        //                <automaticallyAddedFromSDKMAN />
        //            </configuration>
        //        </toolchain>

        XPath xPath = XPathFactory.newInstance().newXPath();

        // Make sure toolchains element is present
        Element toolchains = (Element) xPath.compile("/toolchains").evaluate(document, XPathConstants.NODE);
        if (toolchains == null) {
            toolchains = document.createElement("toolchains");
            document.appendChild(toolchains);
        }
        
        // Scan all versions
        for (SdkmanJavaVersion sdkmanJavaVersion : sdkmanJavaVersions) {
            
            // See if a jdk with the version/vendor combination already exists 
            NodeList exists = (NodeList) xPath.compile("/toolchains/toolchain/type[text()='jdk']/../provides/version[text()='" + sdkmanJavaVersion.version + "']/../vendor[text()='" + sdkmanJavaVersion.vendor + "']").evaluate(document, XPathConstants.NODESET);
            if (exists != null && exists.getLength() > 0) {
                System.out.println("* " + sdkmanJavaVersion.identifier + " already exists");
                continue;
            }
            
            // add a toolchain element
            Element toolchain = createElement(document, toolchains, "toolchain");
            createElementWithText(document, toolchain, "type", "jdk");
            Element provides = createElement(document, toolchain, "provides");
            createElementWithText(document, provides, "version", sdkmanJavaVersion.version);
            createElementWithText(document, provides, "vendor", sdkmanJavaVersion.vendor);
            Element configuration = createElement(document, toolchain, "configuration");
            createElementWithText(document, configuration, "jdkHome", System.getProperty("user.home") + "/.sdkman/candidates/java/" + sdkmanJavaVersion.identifier);
            createElement(document, configuration, MARKER_TAG);
            System.out.println("* " + sdkmanJavaVersion.identifier + " added");
        }
    }
    
    /* */
    private Element createElement(Document document, Element parent, String tagId) {
        Element element = document.createElement(tagId);
        parent.appendChild(element);            
        return element;
    }
    
    /* */
    private Element createElementWithText(Document document, Element parent, String tagId, String text) {
        Element element = document.createElement(tagId);
        parent.appendChild(element);            
        Text textNode = document.createTextNode(text);
        element.appendChild(textNode);
        return element;
    }
    
    /* */
    private void prettyPrint(Document doc, File toolchainsFile) throws TransformerException, ClassNotFoundException, InstantiationException, IllegalAccessException, ClassCastException, FileNotFoundException, IOException {
        try (
            FileOutputStream fileOutputStream = new FileOutputStream(toolchainsFile);
        ) {
            DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
            DOMImplementationLS impl = (DOMImplementationLS) registry.getDOMImplementation("LS");
            LSSerializer serializer = impl.createLSSerializer();
            serializer.setNewLine("\n");
            serializer.getDomConfig().setParameter("format-pretty-print",Boolean.TRUE);
            LSOutput output = impl.createLSOutput();
            output.setEncoding(UTF_8);
            output.setByteStream(fileOutputStream);
            serializer.write(doc, output);
        }
    }
}
