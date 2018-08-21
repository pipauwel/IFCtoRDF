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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * @author lewismc
 *
 */
public class TestIfcSpfReader {

  private IfcSpfReader reader;

  private static final String testInputTTL = "showfiles/Barcelona_Pavilion.ttl";
  private static final String testOutputTTL = "test_Barcelona_Pavilion.ttl";

  /**
   * @throws java.lang.Exception
   */
  @Before
  public void setUp() throws Exception {
    reader = new IfcSpfReader();
  }

  /**
   * @throws java.lang.Exception
   */
  @After
  public void tearDown() throws Exception {
    reader = null;
    Path testFilePath = FileSystems.getDefault().getPath(testOutputTTL);
    try {
      Files.deleteIfExists(testFilePath);
    } catch (NoSuchFileException x) {
      System.err.format("%s: no such" + " file or directory%n", testFilePath);
    } catch (DirectoryNotEmptyException x) {
      System.err.format("%s not empty%n", testFilePath);
    } catch (IOException x) {
      // File permission problems are caught here.
      System.err.println(x);
    }
  }

  /**
   * Test method for {@link be.ugent.IfcSpfReader#showFiles(java.lang.String)}.
   */
  @Test
  public final void testShowFiles() {
    List<String> fileList = IfcSpfReader.showFiles(getClass().getClassLoader().getResource("showfiles").getFile());
    List<String> files = new ArrayList<>();
    for (String file : fileList) {
      files.add(file.substring(file.lastIndexOf("/")+1));
    }
    Assert.assertEquals(Arrays.asList(
            "Barcelona_Pavilion.ifc",
            "Barcelona_Pavilion.ttl",
            "20160414office_model_CV2_fordesign.ifc",
            "20160414office_model_CV2_fordesign.ttl",
            "ootest.txt"), files);
  }

  /**
   * Test method for {@link be.ugent.IfcSpfReader#slurp(java.io.InputStream)}.
   */
  @Test
  public final void testSlurp() {
    //reader.slurp(in)
  }

  /**
   * Test method for {@link be.ugent.IfcSpfReader#convert(java.lang.String, java.lang.String, java.lang.String)}.
   */
  @Test
  public final void testConvertIFCFileToOutputTTL() {
    String ifcFile = null;
    try {
      ifcFile = getClass().getClassLoader().getResource("showfiles/Barcelona_Pavilion.ifc").getFile();
      reader.convert(ifcFile, testOutputTTL, "https://w3id.org/express");
    } catch (IOException e) {
      e.printStackTrace();
    }
    try {
      Assert.assertTrue(compareFileContents(testInputTTL, testOutputTTL));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Method to read the string contents of two files and compare 
   * for equality.
   * @param testInputTTL the expected input TTL
   * @param testOutputTTL the generated output TTL
   * @return true if contents are identical.
   * @throws IOException if there is an error loading method parameters
   * @throws URISyntaxException 
   */
  private boolean compareFileContents(String testInputTTL, String testOutputTTL) throws IOException {
    FileInputStream finInput =  new FileInputStream(getClass().getClassLoader().getResource(testInputTTL).getFile());
    @SuppressWarnings("resource")
    BufferedReader brInput = new BufferedReader(new InputStreamReader(finInput));
    StringBuilder sbInput = new StringBuilder();
    String lineInput;
    while ((lineInput = brInput.readLine()) != null) {  
      sbInput.append(lineInput);
    }

    FileInputStream finOutput =  new FileInputStream(testOutputTTL);
    @SuppressWarnings("resource")
    BufferedReader brOutput = new BufferedReader(new InputStreamReader(finOutput));
    StringBuilder sbOutput = new StringBuilder();
    String lineOutput;
    while ((lineOutput = brOutput.readLine()) != null) {  
      sbOutput.append(lineOutput);
    }

    if (sbInput.toString().equals(sbOutput.toString())) {
      return true;
    } else {
      return false;
    }
  }
}
