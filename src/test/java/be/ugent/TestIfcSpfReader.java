/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package be.ugent;

import org.apache.jena.ext.com.google.common.collect.Streams;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphUtil;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphMatcher;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.sparql.graph.GraphOps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.*;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

/**
 * @author lewismc
 */
public class TestIfcSpfReader {

    private IfcSpfReader reader;

    // private static final String testInputTTL =
    // "showfiles/Barcelona_Pavilion.ttl";
    // private static final String testOutputTTL =
    // "target/test_Barcelona_Pavilion.ttl";

    /**
     * @throws java.lang.Exception
     */
    @BeforeEach
    public void setUp() throws Exception {
        reader = new IfcSpfReader();
    }

    /**
     * @throws java.lang.Exception
     */
    @AfterEach
    public void tearDown() {
        reader = null;
    }

    /**
     * Test method for {@link be.ugent.IfcSpfReader#showFiles(java.lang.String)}
     * .
     */
    @Test
    public final void testShowFiles() {
        String f = getClass().getClassLoader().getResource("showfiles").getFile().replaceAll("%20"," ");
        List<String> fileList = IfcSpfReader.showFiles(f);
        List<String> files = new ArrayList<>();
        for (String file : fileList) {
            files.add(file.substring(file.lastIndexOf(File.separatorChar) + 1));
        }
        java.util.Collections.sort(files);
        StringBuilder sb = new StringBuilder();
        for (String s : files) {
            sb.append(s);
            sb.append(", ");
        }
        Assertions.assertEquals(
                "20160414office_model_CV2_fordesign.ifc, 20160414office_model_CV2_fordesign.ttl, Barcelona_Pavilion.ifc, Barcelona_Pavilion.ttl, ootest.txt, ",
                sb.toString());
    }

    /**
     * Test method for
     * {@link be.ugent.IfcSpfReader#convert(java.lang.String, java.lang.String, java.lang.String)}
     * .
     *
     * @throws IOException
     */
    @Test
    public final void testConvertIFCFileToOutputTTL() throws IOException {
        final List<String> inputFiles;
        inputFiles = showAllFiles(TestIfcSpfReader.class.getClassLoader().getResource("convertIFCFileToOutputTTL").getFile());
        //List<Arguments> result = new ArrayList();

        for (int i = 0; i < inputFiles.size(); ++i) {
            String inputFile = inputFiles.get(i);
            String outputFile = "";
            if (inputFile.endsWith(".ifc")) {
                outputFile = inputFile.substring(0, inputFile.length() - 4) + ".ttl";
                //result.add(Arguments.of(new File(inputFile), new File(outputFile)));

                Graph expected = GraphFactory.createGraphMem();
                RDFDataMgr.read(expected, new FileInputStream(new File(outputFile).getAbsolutePath()), Lang.TTL);
                reader.setup(new File(inputFile).getAbsolutePath());
                Graph actual = reader.convert(new File(inputFile).getAbsolutePath(), "http://linkedbuildingdata.net/ifc/resources/");
                if (!expected.isIsomorphicWith(actual)) {
                    int expectedSize = expected.size();
                    int actualSize = actual.size();
                    Graph intersection = GraphFactory.createGraphMem();
                    GraphOps.addAll(intersection, expected.stream().filter(actual::contains).iterator());
                    int intersectionSize = intersection.size();
                    GraphOps.deleteAll(expected, intersection.find());
                    GraphOps.deleteAll(actual, intersection.find());
                    StringWriter intersectionAsTTl = new StringWriter();
                    RDFDataMgr.write(intersectionAsTTl, intersection, Lang.TTL);
                    StringWriter actualAsTTl = new StringWriter();
                    RDFDataMgr.write(actualAsTTl, actual, Lang.TTL);
                    StringWriter expectedAsTTl = new StringWriter();
                    RDFDataMgr.write(expectedAsTTl, expected, Lang.TTL);
                    String message = String.format(
                            "Test Failed!\n"
                                    + "  Input: %s\n"
                                    + "  Expected output: %s\n"
                                    + "  Expected size: %d\n"
                                    + "  Actual size: %d\n"
                                    + "  Intersection size: %d\n"
                                    + "  In expected and actual:\n%s\n"
                                    + "  Only in expected:\n%s\n"
                                    + "  Only in actual:\n%s\n",
                            inputFile, outputFile, expectedSize, actualSize, intersectionSize,
                            intersectionAsTTl.toString(), expectedAsTTl.toString(), actualAsTTl.toString());
                    Assertions.fail(message);
                }
            }
        }



    }

    /*public static Stream<Arguments> testConvertIFCFileToOutputTTL() {
        final List<String> inputFiles;
        inputFiles = showAllFiles(TestIfcSpfReader.class.getClassLoader().getResource("convertIFCFileToOutputTTL").getFile());
        List<Arguments> result = new ArrayList();
        for (int i = 0; i < inputFiles.size(); ++i) {
            final String inputFile = inputFiles.get(i);
            final String outputFile;
            if (inputFile.endsWith(".ifc")) {
                outputFile = inputFile.substring(0, inputFile.length() - 4) + ".ttl";
                result.add(Arguments.of(new File(inputFile), new File(outputFile)));
            }
        }
        return result.stream();
    }*/

    /*/**
     * Method to read the string contents of two files and compare for equality.
     *
     * @param testInputTTL  the expected input TTL
     * @param testOutputTTL the generated output TTL
     * @return true if contents are identical.
     * @throws IOException        if there is an error loading method parameters
     * @throws URISyntaxException
     */
    /*private void compareFileContents(String testInputTTL, String testOutputTTL) throws IOException {
        FileInputStream finInput = new FileInputStream(testInputTTL);
        FileInputStream finOutput = new FileInputStream(testOutputTTL);
        BufferedReader brInput = new BufferedReader(new InputStreamReader(finInput));
        BufferedReader brOutput = new BufferedReader(new InputStreamReader(finOutput));
        String lineInput;
        String lineOutput;
        int line = 0;
        boolean reading = true;
        while (reading) {
            lineInput = brInput.readLine().trim();
            lineOutput = brOutput.readLine().trim();
            failForDifferentFileLength(lineInput, lineOutput);
            line++;
            if (!lineInput.equals(lineOutput)) {
                Assertions.fail(String.format("Actual output differs from expected output:\n" + "  input: file %s\n" + "  expected output file: %s\n" + "  difference at line %d\n"
                        + "  expected line:%s\n" + "    actual line:%s", testInputTTL, testOutputTTL, line, lineInput, lineOutput));
            }
        }
    }*/

    private void failForDifferentFileLength(String lineInput, String lineOutput) {
        if (lineInput == null && lineOutput != null) {
            Assertions.fail("actual ouput file is longer than expected output file");
        }
        if (lineInput != null && lineOutput == null) {
            Assertions.fail("actual ouput file is shorter than expected output file");
        }
    }

    /**
     * List all files in a particular directory.
     *
     * @param dir the input directory for which you wish to list file.
     * @return a {@link java.util.List} of Strings denoting files.
     */
    public static List<String> showAllFiles(String dir) {
        List<String> goodFiles = new ArrayList<>();

        File folder = new File(dir);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles != null && listOfFiles.length > 0) {
            for (int i = 0; i < listOfFiles.length; i++) {
                if (listOfFiles[i].isFile())
                    goodFiles.add(listOfFiles[i].getAbsolutePath());
                else if (listOfFiles[i].isDirectory())
                    goodFiles.addAll(showAllFiles(listOfFiles[i].getAbsolutePath()));
            }
        }

        return goodFiles;
    }
}
