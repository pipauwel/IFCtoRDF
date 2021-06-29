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

import be.ugent.progress.TaskProgressListener;
import org.apache.jena.graph.Graph;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.sparql.graph.GraphOps;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.invoke.MethodHandles;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

/**
 * @author lewismc
 * 
 */
public class TestIfcSpfReader {
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

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
    List<String> fileList = IfcSpfReader.showFiles(getClass().getClassLoader().getResource("showfiles").getFile());
    List<String> files = new ArrayList<>();
    for (String file : fileList) {
      files.add(file.substring(file.lastIndexOf(File.separatorChar)+1));
    }
    java.util.Collections.sort(files);
    StringBuilder sb = new StringBuilder();
    for (String s : files)
    {
      sb.append(s);
      sb.append(", ");
    }
    Assertions.assertEquals(
            "20160414office_model_CV2_fordesign.ifc, 20160414office_model_CV2_fordesign.ttl, Barcelona_Pavilion.ifc, Barcelona_Pavilion.ttl, ootest.txt, ",
            sb.toString());
  }

    @Test
    @Disabled
    public void testLargeFile() throws IOException {
        // URL resource =
        // getClass().getResource("/showfiles/Barcelona_Pavilion.ifc");
        File file = new File("C:\\Users\\fkleedorfer\\Nextcloud2\\Projekte\\2020-AF öbv merkmalservice\\partner-data\\asfinag\\autobahnmeisterei\\ABM_ARCH.ifc");
        reader.setup(file.getAbsolutePath(), new TaskProgressListener() {
            @Override
            public void notifyProgress(String task, String message, float level) {
                logger.debug("{}: {} ({}%)", task, message, String.format("%.0f", level * 100));
            }

            @Override
            public void notifyFinished(String task) {
                logger.debug("{}: {} ({}%)", task, "finished", String.format("%.0f", 100));
            }
        });
        reader.convert(file.getAbsolutePath(), StreamRDFLib.sinkNull(), "http://linkedbuildingdata.net/ifc/resources/");
    }

    /**
     * Test method for {@link be.ugent.IfcSpfReader#slurp(java.io.InputStream)}.
     */
    @Test
    public final void testSlurp() {
        // reader.slurp(in)
    }

    /**
     * Test method for
     * {@link be.ugent.IfcSpfReader#convert(java.lang.String, java.lang.String, java.lang.String)}
     * .
     * 
     * @throws IOException
     *             if there is an error executing
     *             {@link TestIfcSpfReader#compareFileContents(String, String)}
     */
    @ParameterizedTest
    @MethodSource
    public final void testConvert(File input, File expectedOutput) throws IOException {
        reader.setup(input.getAbsolutePath());
        reader.setRemoveDuplicates(false);
        reader.setAvoidDuplicatePropertyResources(false);
        doTest(input, expectedOutput);
    }

    public static Stream<Arguments> testConvert() {
        final List<String> inputFiles;
        inputFiles = showAllFiles(TestIfcSpfReader.class.getClassLoader().getResource("convert").getFile());
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
    }

    @ParameterizedTest
    @MethodSource
    public final void testConvert_avoidDuplicatePropertyResources(File input, File expectedOutput) throws IOException {
        reader.setup(input.getAbsolutePath());
        reader.setRemoveDuplicates(false);
        reader.setAvoidDuplicatePropertyResources(true);
        doTest(input, expectedOutput);
    }

    public static Stream<Arguments> testConvert_avoidDuplicatePropertyResources() {
        final List<String> inputFiles;
        inputFiles = showAllFiles(TestIfcSpfReader.class.getClassLoader().getResource("convert_avoidDuplicatePropertyResources").getFile());
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
    }

    private void doTest(File input, File expectedOutput) throws IOException {
        Graph expected = GraphFactory.createGraphMem();
        RDFDataMgr.read(expected, new FileInputStream(expectedOutput), Lang.TTL);
        Graph actual = reader.convert(input.getAbsolutePath(), "http://linkedbuildingdata.net/ifc/resources/");
        actual.getPrefixMapping().setNsPrefixes(expected.getPrefixMapping());
        File out = new File(expectedOutput.getParent(), expectedOutput.getName() + ".actual.ttl");
        System.out.println("writing to: " + out);
        RDFDataMgr.write(new FileOutputStream(out), actual, Lang.TTL);
        int expectedSize = expected.size();
        int actualSize = actual.size();
        if (!expected.isIsomorphicWith(actual)){
            Graph intersection = GraphFactory.createGraphMem();
            intersection.getPrefixMapping().setNsPrefixes(expected.getPrefixMapping());
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
                            + "  Input             : %s\n"
                            + "  Expected output   : %s\n"
                            + "  Expected size     : %d\n"
                            + "  Actual size       : %d\n"
                            + "  Intersection size : %d\n"
                            + "\nIn expected and actual:\n%s\n"
                            + "\nOnly in expected:\n%s\n"
                            + "\nOnly in actual: \n%s\n",
                            input.getName(), expectedOutput.getName(), expectedSize, actualSize, intersectionSize,
                            intersectionAsTTl.toString(), expectedAsTTl.toString(), actualAsTTl.toString());
            Assertions.fail(message);
        }
    }

    /**
     * Method to read the string contents of two files and compare for equality.
     * 
     * @param testInputTTL
     *            the expected input TTL
     * @param testOutputTTL
     *            the generated output TTL
     * @return true if contents are identical.
     * @throws IOException
     *             if there is an error loading method parameters
     * @throws URISyntaxException
     */
    private void compareFileContents(String testInputTTL, String testOutputTTL) throws IOException {
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
    }

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
     * @param dir
     *            the input directory for which you wish to list file.
     * @return a {@link java.util.List} of Strings denoting files.
     */
    public static List<String> showAllFiles(String dir) {
		List<String> goodFiles = new ArrayList<>();

		File folder = new File(dir);
		File[] listOfFiles = folder.listFiles();

		for (int i = 0; i < listOfFiles.length; i++) {
			if (listOfFiles[i].isFile())
				goodFiles.add(listOfFiles[i].getAbsolutePath());
			else if (listOfFiles[i].isDirectory())
				goodFiles.addAll(showAllFiles(listOfFiles[i].getAbsolutePath()));
		}
		Pattern fileNumberPattern = Pattern.compile( ".+test(\\d+).(ttl|ifc)");
        goodFiles.sort((f1, f2) -> {
            Matcher m = fileNumberPattern.matcher(f1);
            int num1 = m.matches() ? Integer.valueOf(m.group(1)) : Integer.MAX_VALUE;
            m = fileNumberPattern.matcher(f2);
            int num2 = m.matches() ? Integer.valueOf(m.group(1)) : Integer.MAX_VALUE;
            return num1 - num2;
        });
		return goodFiles;
	}
}
