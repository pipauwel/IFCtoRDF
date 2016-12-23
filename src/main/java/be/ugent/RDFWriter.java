package be.ugent;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.ontology.OntResource;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFWriter;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.openbimstandards.ifcowl.ExpressReader;
import org.openbimstandards.vo.EntityVO;
import org.openbimstandards.vo.IFCVO;
import org.openbimstandards.vo.TypeVO;

import fi.ni.rdf.Namespace;

/*
 * Copyright 2016 Pieter Pauwels, Ghent University; Jyrki Oraskari, Aalto University; Lewis John McGibbney, Apache
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License atf
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class RDFWriter {

    // input variables
    private final String baseURI;
    private final String ontNS;
    private static final String expressURI = "https://w3id.org/express";
    private static final String expressNS = expressURI + "#";
    private static final String listURI = "https://w3id.org/list";
    private static final String listNS = listURI + "#";

    // EXPRESS basis
    private final Map<String, EntityVO> ent;
    private final Map<String, TypeVO> typ;

    // conversion variables
    private int IDcounter = 0;
    private Map<Long, IFCVO> linemap = new HashMap<Long, IFCVO>();

    private StreamRDF ttlWriter;
    private InputStream inputStream;
    private final OntModel ontModel;
    private final OntModel expressModel;
    private final OntModel listModel;

    private IfcSpfReader myIfcReaderStream;

    // for removing duplicates in line entries
    private Map<String, Resource> listOfUniqueResources = new HashMap<String, Resource>();
    private Map<Long, Long> listOfDuplicateLineEntries = new HashMap<Long, Long>();

    // Taking care of avoiding duplicate resources
    private Map<String, Resource> propertyResourceMap = new HashMap<String, Resource>();
    private Map<String, Resource> resourceMap = new HashMap<String, Resource>();

    public RDFWriter(OntModel ontModel, OntModel expressModel, OntModel listModel, InputStream inputStream, String baseURI, Map<String, EntityVO> ent, Map<String, TypeVO> typ, String ontURI) {
        this.ontModel = ontModel;
        this.expressModel = expressModel;
        this.listModel = listModel;
        this.inputStream = inputStream;
        this.baseURI = baseURI;
        this.ent = ent;
        this.typ = typ;
        this.ontNS = ontURI + "#";
    }

    public void setIfcReader(IfcSpfReader r) {
        this.myIfcReaderStream = r;
    }

    public void parseModel2Stream(OutputStream out) throws IOException {
        ttlWriter = StreamRDFWriter.getWriterStream(out, RDFFormat.TURTLE_BLOCKS);
        ttlWriter.base(baseURI);
        ttlWriter.prefix("ifcowl", ontNS);
        ttlWriter.prefix("inst", baseURI);
        ttlWriter.prefix("list", listNS);
        ttlWriter.prefix("express", expressNS);
        ttlWriter.prefix("rdf", Namespace.RDF);
        ttlWriter.prefix("xsd", Namespace.XSD);
        ttlWriter.prefix("owl", Namespace.OWL);
        ttlWriter.start();

        ttlWriter.triple(new Triple(NodeFactory.createURI(baseURI), RDF.type.asNode(), OWL.Ontology.asNode()));
        ttlWriter.triple(new Triple(NodeFactory.createURI(baseURI), OWL.imports.asNode(), NodeFactory.createURI(ontNS)));

        // Read the whole file into a linemap Map object
        readModel();

        System.out.println("model parsed");

        resolveDuplicates();

        // map entries of the linemap Map object to the ontology Model and make
        // new instances in the model
        mapEntries();

        System.out.println("entries mapped, now creating instances");
        createInstances();

        // Save memory
        linemap.clear();
        linemap = null;

        ttlWriter.finish();
    }

    private void readModel() {
        try {
            DataInputStream in = new DataInputStream(inputStream);
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            try {
                String strLine;
                while ((strLine = br.readLine()) != null) {
                    if (strLine.length() > 0) {
                        if (strLine.charAt(0) == '#') {
                            StringBuffer sb = new StringBuffer();
                            String stmp = strLine;
                            sb.append(stmp.trim());
                            while (!stmp.contains(";")) {
                                stmp = br.readLine();
                                if (stmp == null)
                                    break;
                                sb.append(stmp.trim());
                            }
                            // the whole IFC gets parsed, and everything ends up
                            // as IFCVO objects in the Map<Long, IFCVO> linemap
                            // variable
                            parseIfcLineStatement(sb.toString().substring(1));
                        }
                    }
                }
            } finally {
                br.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void parseIfcLineStatement(String line) {
        IFCVO ifcvo = new IFCVO();
        ifcvo.setFullLineAfterNum(line.substring(line.indexOf("=") + 1));
        int state = 0;
        StringBuffer sb = new StringBuffer();
        int clCount = 0;
        LinkedList<Object> current = (LinkedList<Object>) ifcvo.getObjectList();
        Stack<LinkedList<Object>> listStack = new Stack<LinkedList<Object>>();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            switch (state) {
                case 0:
                if (ch == '=') {
                    ifcvo.setLineNum(toLong(sb.toString()));
                    sb.setLength(0);
                    state++;
                    continue;
                } else if (Character.isDigit(ch))
                    sb.append(ch);
                    break;
                case 1: // (
                if (ch == '(') {
                    ifcvo.setName(sb.toString());
                    sb.setLength(0);
                    state++;
                    continue;
                } else if (ch == ';') {
                    ifcvo.setName(sb.toString());
                    sb.setLength(0);
                    state = Integer.MAX_VALUE;
                } else if (!Character.isWhitespace(ch))
                    sb.append(ch);
                    break;
                case 2: // (... line started and doing (...
                if (ch == '\'') {
                    state++;
                }
                if (ch == '(') {
                    listStack.push(current);
                    LinkedList<Object> tmp = new LinkedList<Object>();
                    if (sb.toString().trim().length() > 0)
                        current.add(sb.toString().trim());
                    sb.setLength(0);
                    current.add(tmp);
                    current = tmp;
                    clCount++;
                    // sb.append(ch);
                } else if (ch == ')') {
                    if (clCount == 0) {
                        if (sb.toString().trim().length() > 0)
                            current.add(sb.toString().trim());
                        sb.setLength(0);
                        state = Integer.MAX_VALUE; // line is done
                        continue;
                    } else {
                        if (sb.toString().trim().length() > 0)
                            current.add(sb.toString().trim());
                        sb.setLength(0);
                        clCount--;
                        current = listStack.pop();
                    }
                } else if (ch == ',') {
                    if (sb.toString().trim().length() > 0)
                        current.add(sb.toString().trim());
                    current.add(Character.valueOf(ch));

                    sb.setLength(0);
                } else {
                    sb.append(ch);
                }
                    break;
                case 3: // (...
                if (ch == '\'') {
                    state--;
                } else {
                    sb.append(ch);
                }
                    break;
                default:
                // Do nothing
            }
        }
        linemap.put(ifcvo.getLineNum(), ifcvo);
        IDcounter++;
    }

    private void resolveDuplicates() throws IOException {
        Map<String, IFCVO> listOfUniqueResources = new HashMap<String, IFCVO>();
        List<Long> entriesToRemove = new ArrayList<Long>();
        for (Map.Entry<Long, IFCVO> entry : linemap.entrySet()) {
            IFCVO vo = entry.getValue();
            String t = vo.getFullLineAfterNum();
            if (!listOfUniqueResources.containsKey(t))
                listOfUniqueResources.put(t, vo);
            else {
                // found duplicate
                entriesToRemove.add(entry.getKey());// linemap.remove(entry.getKey());
                listOfDuplicateLineEntries.put(vo.getLineNum(), listOfUniqueResources.get(t).getLineNum());
            }
        }
        if (myIfcReaderStream.logToFile)
            myIfcReaderStream.bw.write("found and removed " + listOfDuplicateLineEntries.size() + " duplicates! \r\n");
        for (Long x : entriesToRemove) {
            linemap.remove(x);
        }
    }

    private void mapEntries() {
        for (Map.Entry<Long, IFCVO> entry : linemap.entrySet()) {
            IFCVO vo = entry.getValue();

            // mapping properties to IFCVOs
            for (int i = 0; i < vo.getObjectList().size(); i++) {
                Object o = vo.getObjectList().get(i);
                if (String.class.isInstance(o)) {
                    String s = (String) o;
                    if (s.length() < 1)
                        continue;
                    if (s.charAt(0) == '#') {
                        Object or = null;
                        if (listOfDuplicateLineEntries.containsKey(toLong(s.substring(1))))
                            or = linemap.get(listOfDuplicateLineEntries.get(toLong(s.substring(1))));
                        else
                            or = linemap.get(toLong(s.substring(1)));
                        vo.getObjectList().set(i, or);
                    }
                }
                if (LinkedList.class.isInstance(o)) {
                    @SuppressWarnings("unchecked")
                    LinkedList<Object> tmpList = (LinkedList<Object>) o;

                    for (int j = 0; j < tmpList.size(); j++) {
                        Object o1 = tmpList.get(j);
                        if (String.class.isInstance(o1)) {
                            String s = (String) o1;
                            if (s.length() < 1)
                                continue;
                            if (s.charAt(0) == '#') {
                                Object or = null;
                                if (listOfDuplicateLineEntries.containsKey(toLong(s.substring(1))))
                                    or = linemap.get(listOfDuplicateLineEntries.get(toLong(s.substring(1))));
                                else
                                    or = linemap.get(toLong(s.substring(1)));
                                if (or == null) {
                                    System.err.println("Reference to non-existing line in the IFC file.");
                                    tmpList.set(j, "-");
                                } else
                                    tmpList.set(j, or);
                            }
                        } else if (LinkedList.class.isInstance(o1)) {
                            @SuppressWarnings("unchecked")
                            LinkedList<Object> tmp2List = (LinkedList<Object>) o1;
                            for (int j2 = 0; j2 < tmp2List.size(); j2++) {
                                Object o2 = tmp2List.get(j2);
                                if (String.class.isInstance(o2)) {
                                    String s = (String) o2;
                                    if (s.length() < 1)
                                        continue;
                                    if (s.charAt(0) == '#') {
                                        Object or = null;
                                        if (listOfDuplicateLineEntries.containsKey(toLong(s.substring(1))))
                                            or = linemap.get(listOfDuplicateLineEntries.get(toLong(s.substring(1))));
                                        else
                                            or = linemap.get(toLong(s.substring(1)));
                                        if (or == null) {
                                            System.err.println("Reference to non-existing line in the IFC file.");
                                            tmpList.set(j, "-");
                                        } else
                                            tmpList.set(j, or);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void createInstances() throws IOException {
        //int i = 0;
        for (Map.Entry<Long, IFCVO> entry : linemap.entrySet()) {
            IFCVO ifcLineEntry = entry.getValue();
            String typeName = "";
            if (ent.containsKey(ifcLineEntry.getName()))
                typeName = ent.get(ifcLineEntry.getName()).getName();
            else if (typ.containsKey(ifcLineEntry.getName()))
                typeName = typ.get(ifcLineEntry.getName()).getName();

            OntClass cl = ontModel.getOntClass(ontNS + typeName);

            Resource r = getResource(baseURI + typeName + "_" + ifcLineEntry.getLineNum(), cl);
            listOfUniqueResources.put(ifcLineEntry.getFullLineAfterNum(), r);

            if (myIfcReaderStream.logToFile)
                myIfcReaderStream.bw.write("-------------------------------" + "\r\n");
            if (myIfcReaderStream.logToFile)
                myIfcReaderStream.bw.write(r.getLocalName() + "\r\n");
            if (myIfcReaderStream.logToFile)
                myIfcReaderStream.bw.write("-------------------------------" + "\r\n");

            fillProperties(ifcLineEntry, r, cl);
            //i++;
        }
        // The map is used only to avoid duplicates.
        // So, it can be cleared here
        propertyResourceMap.clear();
    }

    TypeVO typeremembrance = null;

    private void fillProperties(IFCVO ifcLineEntry, Resource r, OntClass cl) throws IOException {

        EntityVO evo = ent.get(ExpressReader.formatClassName(ifcLineEntry.getName()));
        TypeVO tvo = typ.get(ExpressReader.formatClassName(ifcLineEntry.getName()));

        if (tvo == null && evo == null) {
            System.err.println("Type nor entity exists: " + ifcLineEntry.getName());
        }

        if (evo == null && tvo != null) {
            // System.err.println("Entity does not exist: " +
            // ifcLineEntry.getName());
            //final String subject = tvo.getName() + "_" + ifcLineEntry.getLineNum();

            typeremembrance = null;
            //int attributePointer = 0;
            for (Object o : ifcLineEntry.getObjectList()) {

                if (String.class.isInstance(o)) {
                    System.out.println("WARNING: unhandled type property found.");
                    // attributePointer = fillPropertiesHandleStringObject(r,
                    // tvo,
                    // subject, attributePointer, o);
                } else if (IFCVO.class.isInstance(o)) {
                    System.out.println("WARNING: unhandled type property found.");
                    // attributePointer = fillPropertiesHandleIFC_Object(r,
                    // tvo, attributePointer, o);
                } else if (LinkedList.class.isInstance(o)) {
                    fillPropertiesHandleListObject(r, tvo, o);
                }
                if (myIfcReaderStream.logToFile)
                    myIfcReaderStream.bw.flush();
            }
        }

        if (tvo == null && evo != null) {
            // System.err.println("Type does not exist: " +
            // ifcLineEntry.getName());
            final String subject = evo.getName() + "_" + ifcLineEntry.getLineNum();

            typeremembrance = null;
            int attributePointer = 0;
            for (Object o : ifcLineEntry.getObjectList()) {

                if (String.class.isInstance(o)) {
                    attributePointer = fillPropertiesHandleStringObject(r, evo, subject, attributePointer, o);
                } else if (IFCVO.class.isInstance(o)) {
                    attributePointer = fillPropertiesHandleIfcObject(r, evo, attributePointer, o);
                } else if (LinkedList.class.isInstance(o)) {
                    attributePointer = fillPropertiesHandleListObject(r, evo, attributePointer, o);
                }
                if (myIfcReaderStream.logToFile)
                    myIfcReaderStream.bw.flush();
            }
        }

        if (myIfcReaderStream.logToFile)
            myIfcReaderStream.bw.flush();
    }

    private int fillPropertiesHandleStringObject(Resource r, EntityVO evo, String subject, int attributePointer, Object o) throws IOException {
        if (!((String) o).equals("$") && !((String) o).equals("*")) {

            if (typ.get(ExpressReader.formatClassName((String) o)) == null) {
                if ((evo != null) && (evo.getDerivedInverseList() != null) && (evo.getDerivedInverseList().size() > attributePointer)) {

                    final String propURI = ontNS + evo.getDerivedInverseList().get(attributePointer).getLowerCaseName();
                    final String literalString = filterExtras((String) o);

                    OntProperty p = ontModel.getOntProperty(propURI);
                    OntResource range = p.getRange();
                    if (range.isClass()) {
                        // Iterator<OntClass> x =
                        // range.asClass().listSuperClasses();
                        // while(x.hasNext()){
                        // System.out.println("found superclass : " +
                        // x.next().getLocalName());
                        // }
                        OntClass c = expressModel.getOntClass(expressNS + "ENUMERATION");
                        if (range.asClass().hasSuperClass(c)) {
                            addEnumProperty(r, p, range, literalString);
                        }
                        // Check for SELECT
                        else if (range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "SELECT"))) {
                            if (myIfcReaderStream.logToFile)
                                myIfcReaderStream.bw.write("1 - WARNING TODO: found SELECT property: " + p + " - " + range.getLocalName() + " - " + literalString + "\r\n");
                        } else if (range.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))) {
                            if (myIfcReaderStream.logToFile)
                                myIfcReaderStream.bw.write("1a - WARNING TODO: found LIST property: " + subject + " -- " + p + " - " + range.getLocalName() + " - " + literalString + "\r\n");
                        } else {
                            System.out.println("getXSDTypeFromRange(range) : " + range);
                            String xsdType = getXSDTypeFromRange(range);
                            if (xsdType == null) {
                                xsdType = getXSDTypeFromRangeExpensiveMethod(range);
                            }
                            if (xsdType != null) {
                                String xsdTypeCAP = Character.toUpperCase(xsdType.charAt(0)) + xsdType.substring(1);
                                OntProperty valueProp = expressModel.getOntProperty(expressNS + "has" + xsdTypeCAP);

                                // Create only when needed...
                                String key = valueProp.toString() + ":" + xsdType + ":" + literalString;
                                Resource r1 = propertyResourceMap.get(key);
                                if (r1 == null) {
                                    r1 = ResourceFactory.createResource(baseURI + range.getLocalName() + "_" + IDcounter);
                                    ttlWriter.triple(new Triple(r1.asNode(), RDF.type.asNode(), range.asNode()));
                                    if (myIfcReaderStream.logToFile)
                                        myIfcReaderStream.bw.write("created resource: " + r1.getLocalName() + "\r\n");
                                    IDcounter++;
                                    propertyResourceMap.put(key, r1);
                                    addLiteralToResource(r1, valueProp, xsdType, literalString);
                                }
                                ttlWriter.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
                                if (myIfcReaderStream.logToFile)
                                    myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName() + "\r\n");
                            } else {
                                if (myIfcReaderStream.logToFile)
                                    myIfcReaderStream.bw.write("1b - WARNING TODO: this should not happen for: " + p + " - " + range.getURI() + " - " + literalString + "\r\n");
                            }
                        }
                    } else {
                        if (myIfcReaderStream.logToFile)
                            myIfcReaderStream.bw.write("5 - WARNING: found other kind of property: " + p + " - " + range.getLocalName() + "\r\n");
                    }
                }
                attributePointer++;
            } else {
                typeremembrance = typ.get(ExpressReader.formatClassName((String) o));
            }
        } else
            attributePointer++;
        return attributePointer;
    }

    private int fillPropertiesHandleIfcObject(Resource r, EntityVO evo, int attributePointer, Object o) throws IOException {
        if ((evo != null) && (evo.getDerivedInverseList() != null) && (evo.getDerivedInverseList().size() > attributePointer)) {

            final String propURI = ontNS + evo.getDerivedInverseList().get(attributePointer).getLowerCaseName();
            EntityVO evorange = ent.get(ExpressReader.formatClassName(((IFCVO) o).getName()));

            OntProperty p = ontModel.getOntProperty(propURI);
            OntResource rclass = ontModel.getOntResource(ontNS + evorange.getName());

            Resource r1 = getResource(baseURI + evorange.getName() + "_" + ((IFCVO) o).getLineNum(), rclass);
            ttlWriter.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
            if (myIfcReaderStream.logToFile)
                myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName() + "\r\n");
        }
        attributePointer++;
        return attributePointer;
    }

    @SuppressWarnings("unchecked")
	private int fillPropertiesHandleListObject(Resource r, EntityVO evo, int attributePointer, Object o) throws IOException {

        final LinkedList<Object> tmpList = (LinkedList<Object>) o;
        LinkedList<String> literals = new LinkedList<String>();

        // process list
        for (int j = 0; j < tmpList.size(); j++) {
            Object o1 = tmpList.get(j);
            if (String.class.isInstance(o1)) {
                if (typ.get(ExpressReader.formatClassName((String) o1)) != null && typeremembrance == null)
                    typeremembrance = typ.get(ExpressReader.formatClassName((String) o1));
                else
                    literals.add(filterExtras((String) o1));
            }
            if (IFCVO.class.isInstance(o1)) {
                if ((evo != null) && (evo.getDerivedInverseList() != null) && (evo.getDerivedInverseList().size() > attributePointer)) {

                    String propURI = evo.getDerivedInverseList().get(attributePointer).getLowerCaseName();
                    OntProperty p = ontModel.getOntProperty(ontNS + propURI);
                    OntResource typerange = p.getRange();

                    if (typerange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))) {
                        // EXPRESS LISTs
                        String listvaluepropURI = ontNS + typerange.getLocalName().substring(0, typerange.getLocalName().length() - 5);
                        OntResource listrange = ontModel.getOntResource(listvaluepropURI);

                        if (listrange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))) {
                            if (myIfcReaderStream.logToFile)
                                myIfcReaderStream.bw.write("6 - WARNING: Found unhandled ListOfList" + "\r\n");
                        } else {
                            fillClassInstanceList(tmpList, typerange, p, r);
                            j = tmpList.size() - 1;
                        }
                    } else {
                        // EXPRESS SETs
                        EntityVO evorange = ent.get(ExpressReader.formatClassName(((IFCVO) o1).getName()));
                        OntResource rclass = ontModel.getOntResource(ontNS + evorange.getName());

                        Resource r1 = getResource(baseURI + evorange.getName() + "_" + ((IFCVO) o1).getLineNum(), rclass);
                        ttlWriter.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
                        if (myIfcReaderStream.logToFile)
                            myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName() + "\r\n");
                    }
                }
            }
            if (LinkedList.class.isInstance(o1) && typeremembrance != null) {
                LinkedList<Object> tmpListInlist = (LinkedList<Object>) o1;
                for (int jj = 0; jj < tmpListInlist.size(); jj++) {
                    Object o2 = tmpListInlist.get(jj);
                    if (String.class.isInstance(o2)) {
                        literals.add(filterExtras((String) o2));
                    } else {
                        System.out.println("do something");
                    }
                }
            }
        }

        // interpret parse
        if (literals.size() > 0) {
            if (typeremembrance != null) {
                if ((evo != null) && (evo.getDerivedInverseList() != null) && (evo.getDerivedInverseList().size() > attributePointer)) {

                    String propURI = ontNS + evo.getDerivedInverseList().get(attributePointer).getLowerCaseName();
                    OntProperty p = ontModel.getOntProperty(propURI);

                    addSinglePropertyFromTypeRemembrance(r, p, literals.getFirst(), typeremembrance);
                }
                typeremembrance = null;
            } else if ((evo != null) && (evo.getDerivedInverseList() != null) && (evo.getDerivedInverseList().size() > attributePointer)) {
                String propURI = ontNS + evo.getDerivedInverseList().get(attributePointer).getLowerCaseName();
                OntProperty p = ontModel.getOntProperty(propURI);
                addRegularListProperty(r, p, literals);
            }
        }
        attributePointer++;
        return attributePointer;
    }

    @SuppressWarnings({ "unchecked" })
	private void fillPropertiesHandleListObject(Resource r, TypeVO tvo, Object o) throws IOException {

        final LinkedList<Object> tmpList = (LinkedList<Object>) o;
        LinkedList<String> literals = new LinkedList<String>();

        // process list
        for (int j = 0; j < tmpList.size(); j++) {
            Object o1 = tmpList.get(j);
            if (String.class.isInstance(o1)) {
                if (typ.get(ExpressReader.formatClassName((String) o1)) != null && typeremembrance == null)
                    typeremembrance = typ.get(ExpressReader.formatClassName((String) o1));
                else
                    literals.add(filterExtras((String) o1));
            }
            if (IFCVO.class.isInstance(o1)) {
                if ((tvo != null)) {
                    if (myIfcReaderStream.logToFile)
                        myIfcReaderStream.bw.write("TODO 16: found TYPE that is equivalent to a list if IFC entities - below is the code used when this happens for ENTITIES with a list of ENTITIES"
                                        + "\r\n");
                    System.out.println("TODO 16: found TYPE that is equivalent to a list if IFC entities - below is the code used when this happens for ENTITIES with a list of ENTITIES");
                    // String propURI =
                    // tvo.evo.getDerivedAttributeList().get(attributePointer).getLowerCaseName();
                    // OntProperty p = ontModel.getOntProperty(ontNS + propURI);
                    // OntResource typerange = p.getRange();
                    //
                    // if(typerange.asClass().hasSuperClass(listModel.getOntClass(listNS
                    // + "OWLList"))){
                    // //EXPRESS LISTs
                    // String listvaluepropURI = ontNS +
                    // typerange.getLocalName().substring(0,
                    // typerange.getLocalName().length()-5);
                    // OntResource listrange =
                    // ontModel.getOntResource(listvaluepropURI);
                    //
                    // if(listrange.asClass().hasSuperClass(listModel.getOntClass(listNS
                    // + "OWLList"))){
                    // if(myIfcReaderStream.logToFile)
                    // myIfcReaderStream.bw.write("6 - WARNING: Found unhandled ListOfList"
                    // + "\r\n");
                    // }
                    // else{
                    // fillClassInstanceList(tmpList, typerange, p, r);
                    // j = tmpList.size()-1;
                    // }
                    // }
                    // else{
                    // //EXPRESS SETs
                    // EntityVO evorange =
                    // ent.get(ExpressReader.formatClassName(((IFCVO)o1).getName()));
                    // OntResource rclass = ontModel.getOntResource(ontNS +
                    // evorange.getName());
                    //
                    // Resource r1 = getResource(baseURI + evorange.getName() +
                    // "_" + ((IFCVO) o1).getLineNum(),rclass);
                    // ttlWriter.triple(new Triple(r.asNode(), p.asNode(),
                    // r1.asNode()));
                    // if(myIfcReaderStream.logToFile)
                    // myIfcReaderStream.bw.write("added property: " +
                    // r.getLocalName() + " - " + p.getLocalName() + " - " +
                    // r1.getLocalName() + "\r\n");
                    // }
                }
            }
            if (LinkedList.class.isInstance(o1) && typeremembrance != null) {
                LinkedList<Object> tmpListInlist = (LinkedList<Object>) o1;
                for (int jj = 0; jj < tmpListInlist.size(); jj++) {
                    Object o2 = tmpListInlist.get(jj);
                    if (String.class.isInstance(o2)) {
                        literals.add(filterExtras((String) o2));
                    }
                }
            }
        }

        // interpret parse
        if (literals.size() > 0) {
            if (typeremembrance != null) {
                if ((tvo != null)) {
                    // && (tvo.getDerivedAttributeList() != null)
                    // && (tvo.getDerivedAttributeList().size() >
                    // attributePointer)) {
                    if (myIfcReaderStream.logToFile)
                        myIfcReaderStream.bw.write("WARNING: this part of the code has not been checked - it can't be correct" + "\r\n");
                    System.out.println("WARNING: this part of the code has not been checked - it can't be correct");

                    String[] primtypeArr = tvo.getPrimarytype().split(" ");
                    String primType = primtypeArr[primtypeArr.length - 1].replace(";", "") + "_" + primtypeArr[0].substring(0, 1).toUpperCase() + primtypeArr[0].substring(1).toLowerCase();
                    String typeURI = ontNS + primType;
                    OntResource range = ontModel.getOntResource(typeURI);
                    addDirectRegularListProperty(r, range, literals);

                    // String propURI = ontNS +
                    // tvo.getName();//.getDerivedAttributeList().get(attributePointer).getLowerCaseName();
                    // OntProperty p = ontModel.getOntProperty(propURI);
                    // addSinglePropertyFromTypeRemembrance(r, p,
                    // literals.getFirst(), typeremembrance);
                }
                typeremembrance = null;
            } else if ((tvo != null)) {
                String[] primtypeArr = tvo.getPrimarytype().split(" ");
                String primType = primtypeArr[primtypeArr.length - 1].replace(";", "") + "_" + primtypeArr[0].substring(0, 1).toUpperCase() + primtypeArr[0].substring(1).toLowerCase();
                String typeURI = ontNS + primType;
                OntResource range = ontModel.getOntResource(typeURI);
                addDirectRegularListProperty(r, range, literals);
            }
        }
    }

    private void addSinglePropertyFromTypeRemembrance(Resource r, OntProperty p, String literalString, TypeVO typeremembrance) throws IOException {
        if (typeremembrance.getPrimarytype().startsWith("LIST")) {
            System.out.println("WARNING: the type is equivalent to a list!!!!!!!");

            // fillPropertiesHandleListObject(r,typeremembrance,typeremembrance);

            // String[] primtypeArr =
            // typeremembrance.getPrimarytype().split(" ");
            // String primType = primtypeArr[primtypeArr.length-1].replace(";",
            // "") + "_" + primtypeArr[0].substring(0,1).toUpperCase() +
            // primtypeArr[0].substring(1).toLowerCase();
            // String typeURI = ontNS + primType;
            // OntResource range = ontModel.getOntResource(typeURI);
            // addDirectRegularListProperty(r, range, literals);
        } else {

            OntResource range = ontModel.getOntResource(ontNS + typeremembrance.getName());

            if (range.isClass()) {
                // Check for ENUM
                if (range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "ENUMERATION"))) {
                    addEnumProperty(r, p, range, literalString);
                }
                // Check for SELECT
                else if (range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "SELECT"))) {
                    if (myIfcReaderStream.logToFile)
                        myIfcReaderStream.bw.write("9 - WARNING TODO: found SELECT property: " + p + " - " + range.getLocalName() + " - " + literalString + "\r\n");
                } else {
                    String xsdType = getXSDTypeFromRange(range);
                    if (xsdType == null)
                        xsdType = getXSDTypeFromRangeExpensiveMethod(range);
                    if (xsdType != null) {
                        String xsdTypeCAP = Character.toUpperCase(xsdType.charAt(0)) + xsdType.substring(1);
                        OntProperty valueProp = expressModel.getOntProperty(expressNS + "has" + xsdTypeCAP);
                        String key = valueProp.toString() + ":" + xsdType + ":" + literalString;

                        Resource r1 = propertyResourceMap.get(key);
                        if (r1 == null) {
                            r1 = ResourceFactory.createResource(baseURI + typeremembrance.getName() + "_" + IDcounter);
                            ttlWriter.triple(new Triple(r1.asNode(), RDF.type.asNode(), range.asNode()));
                            IDcounter++;
                            propertyResourceMap.put(key, r1);
                            addLiteralToResource(r1, valueProp, xsdType, literalString);
                        }
                        ttlWriter.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
                        if (myIfcReaderStream.logToFile)
                            myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName() + "\r\n");
                    }
                }
            } else {
                if (myIfcReaderStream.logToFile)
                    myIfcReaderStream.bw.write("12 - WARNING: found other kind of property: " + p + " - " + range.getLocalName() + "\r\n");
            }
        }
    }

    private void addEnumProperty(Resource r, Property p, OntResource range, String literalString) throws IOException {
        for (ExtendedIterator<? extends OntResource> instances = range.asClass().listInstances(); instances.hasNext();) {
            OntResource rangeInstance = instances.next();
            if (rangeInstance.getProperty(RDFS.label).getString().equalsIgnoreCase(filterPoints(literalString))) {
                ttlWriter.triple(new Triple(r.asNode(), p.asNode(), rangeInstance.asNode()));
                if (myIfcReaderStream.logToFile)
                    myIfcReaderStream.bw.write("added ENUM statement " + r.getLocalName() + " - " + p.getLocalName() + " - " + rangeInstance.getLocalName() + "\r\n");
                break;
            }
        }
    }

    private void addLiteralToResource(Resource r1, OntProperty valueProp, String xsdType, String literalString) throws IOException {
        if (xsdType.equalsIgnoreCase("integer"))
            addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDinteger));
        else if (xsdType.equalsIgnoreCase("double"))
            addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDdouble));
        else if (xsdType.equalsIgnoreCase("hexBinary"))
            addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDhexBinary));
        else if (xsdType.equalsIgnoreCase("boolean")) {
            if (literalString.equalsIgnoreCase(".F."))
                addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral("false", XSDDatatype.XSDboolean));
            else if (literalString.equalsIgnoreCase(".T."))
                addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral("true", XSDDatatype.XSDboolean));
            else if (myIfcReaderStream.logToFile)
                myIfcReaderStream.bw.write("WARNING: found odd boolean value: " + literalString + "\r\n");
        } else if (xsdType.equalsIgnoreCase("logical")) {
            if (literalString.equalsIgnoreCase(".F."))
                addProperty(r1, valueProp, expressModel.getResource(expressNS + "FALSE"));
            else if (literalString.equalsIgnoreCase(".T."))
                addProperty(r1, valueProp, expressModel.getResource(expressNS + "TRUE"));
            else if (literalString.equalsIgnoreCase(".U."))
                addProperty(r1, valueProp, expressModel.getResource(expressNS + "UNKNOWN"));
            else if (myIfcReaderStream.logToFile)
                myIfcReaderStream.bw.write("WARNING: found odd logical value: " + literalString + "\r\n");
        } else if (xsdType.equalsIgnoreCase("string"))
            addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDstring));
        else
            addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral(literalString));

        if (myIfcReaderStream.logToFile)
            myIfcReaderStream.bw.write("added literal: " + r1.getLocalName() + " - " + valueProp + " - " + literalString + "\r\n");
    }

    // LIST HANDLING
    private void addDirectRegularListProperty(Resource r, OntResource range, List<String> el) throws IOException {
        // OntResource range = p.getRange();
        if (range.isClass()) {
            OntResource listrange = getListContentType(range.asClass());

            if (listrange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))) {
                if (myIfcReaderStream.logToFile)
                    myIfcReaderStream.bw.write("14 - WARNING: Found unhandled ListOfList" + "\r\n");
            } else {
                List<Resource> reslist = new ArrayList<Resource>();
                // createrequirednumberofresources
                for (int ii = 0; ii < el.size(); ii++) {
                    if (ii == 0)
                        reslist.add(r);
                    else {
                        Resource r1 = getResource(baseURI + range.getLocalName() + "_" + IDcounter, range);
                        reslist.add(r1);
                        IDcounter++;
                    }
                }
                // bindtheproperties
                addListInstanceProperties(reslist, el, listrange);
            }
        }
    }

    private void addRegularListProperty(Resource r, OntProperty p, List<String> el) throws IOException {
        OntResource range = p.getRange();
        if (range.isClass()) {
            OntResource listrange = getListContentType(range.asClass());

            if (listrange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))) {
                if (myIfcReaderStream.logToFile)
                    myIfcReaderStream.bw.write("14 - WARNING: Found unhandled ListOfList" + "\r\n");
            } else {
                List<Resource> reslist = new ArrayList<Resource>();
                // createrequirednumberofresources
                for (int ii = 0; ii < el.size(); ii++) {
                    Resource r1 = getResource(baseURI + range.getLocalName() + "_" + IDcounter, range);
                    reslist.add(r1);
                    IDcounter++;
                    if (ii == 0) {
                        ttlWriter.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
                        if (myIfcReaderStream.logToFile)
                            myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName() + "\r\n");
                    }
                }
                // bindtheproperties
                addListInstanceProperties(reslist, el, listrange);
            }
        }
    }

//    private List<String> getListElements(String literalString) throws IOException {
//        String[] elements = literalString.split("_, ");
//        List<String> el = new ArrayList<String>();
//        for (String element : elements) {
//            if (element.startsWith("_") && element.endsWith("_"))
//                if (myIfcReaderStream.logToFile)
//                    myIfcReaderStream.bw.write("WARNING getListElements(): Found list of enumerations" + "\r\n");
//            if (element.contains("_")) {
//                if (myIfcReaderStream.logToFile)
//                    myIfcReaderStream.bw.write("WARNING getListElements(): Found '_' in list elements" + "\r\n");
//                element = element.replaceAll("_", "");
//            }
//            el.add(element);
//        }
//        return el;
//    }

    private OntResource getListContentType(OntClass range) throws IOException {
        if (range.asClass().getURI().equalsIgnoreCase(expressNS + "STRING_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "STRING_List")))
            return expressModel.getOntResource(expressNS + "STRING");
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "REAL_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "REAL_List")))
            return expressModel.getOntResource(expressNS + "REAL");
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "INTEGER_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "INTEGER_List")))
            return expressModel.getOntResource(expressNS + "INTEGER");
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "BINARY_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "BINARY_List")))
            return expressModel.getOntResource(expressNS + "BINARY");
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "BOOLEAN_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "BOOLEAN_List")))
            return expressModel.getOntResource(expressNS + "BOOLEAN");
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "LOGICAL_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "LOGICAL_List")))
            return expressModel.getOntResource(expressNS + "LOGICAL");
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "NUMBER_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "NUMBER_List")))
            return expressModel.getOntResource(expressNS + "NUMBER");
        else if (range.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))) {
            String listvaluepropURI = ontNS + range.getLocalName().substring(0, range.getLocalName().length() - 5);
            return ontModel.getOntResource(listvaluepropURI);
        } else {
            if (myIfcReaderStream.logToFile) {
                myIfcReaderStream.bw.write("WARNING: did not find listcontenttype for : " + range.getLocalName() + "\r\n");
            }
            return null;
        }
    }

    private void fillClassInstanceList(LinkedList<Object> tmpList, OntResource typerange, OntProperty p, Resource r) throws IOException {
        List<Resource> reslist = new ArrayList<Resource>();
        List<IFCVO> entlist = new ArrayList<IFCVO>();

        // createrequirednumberofresources
        for (int i = 0; i < tmpList.size(); i++) {
            if (IFCVO.class.isInstance(tmpList.get(i))) {
                Resource r1 = getResource(baseURI + typerange.getLocalName() + "_" + IDcounter, typerange);
                reslist.add(r1);
                IDcounter++;
                entlist.add((IFCVO) tmpList.get(i));
                if (i == 0) {
                    ttlWriter.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
                }
            }
        }

        // bindtheproperties
        String listvaluepropURI = ontNS + typerange.getLocalName().substring(0, typerange.getLocalName().length() - 5);
        OntResource listrange = ontModel.getOntResource(listvaluepropURI);

        addClassInstanceListProperties(reslist, entlist, listrange);
    }

    private void addClassInstanceListProperties(List<Resource> reslist, List<IFCVO> entlist, OntResource listrange) throws IOException {
        OntProperty listp = listModel.getOntProperty(listNS + "hasContents");
        OntProperty isfollowed = listModel.getOntProperty(listNS + "hasNext");

        for (int i = 0; i < reslist.size(); i++) {
            Resource r = reslist.get(i);

            OntResource rclass = null;
            EntityVO evorange = ent.get(ExpressReader.formatClassName(entlist.get(i).getName()));
            if (evorange == null) {
                TypeVO typerange = typ.get(ExpressReader.formatClassName(entlist.get(i).getName()));
                rclass = ontModel.getOntResource(ontNS + typerange.getName());
                Resource r1 = getResource(baseURI + typerange.getName() + "_" + entlist.get(i).getLineNum(), rclass);
                ttlWriter.triple(new Triple(r.asNode(), listp.asNode(), r1.asNode()));
                if (myIfcReaderStream.logToFile)
                    myIfcReaderStream.bw.write("created property: " + r.getLocalName() + " - " + listp.getLocalName() + " - " + r1.getLocalName() + "\r\n");
            } else {
                rclass = ontModel.getOntResource(ontNS + evorange.getName());
                Resource r1 = getResource(baseURI + evorange.getName() + "_" + entlist.get(i).getLineNum(), rclass);
                ttlWriter.triple(new Triple(r.asNode(), listp.asNode(), r1.asNode()));
                if (myIfcReaderStream.logToFile)
                    myIfcReaderStream.bw.write("created property: " + r.getLocalName() + " - " + listp.getLocalName() + " - " + r1.getLocalName() + "\r\n");
            }

            if (i < reslist.size() - 1) {
                ttlWriter.triple(new Triple(r.asNode(), isfollowed.asNode(), reslist.get(i + 1).asNode()));
                if (myIfcReaderStream.logToFile)
                    myIfcReaderStream.bw.write("created property: " + r.getLocalName() + " - " + isfollowed.getLocalName() + " - " + reslist.get(i + 1).getLocalName() + "\r\n");
            }
        }
    }

    private void addListInstanceProperties(List<Resource> reslist, List<String> listelements, OntResource listrange) throws IOException {
        // GetListType
        String xsdType = getXSDTypeFromRange(listrange);
        if (xsdType == null)
            xsdType = getXSDTypeFromRangeExpensiveMethod(listrange);
        if (xsdType != null) {
            String xsdTypeCAP = Character.toUpperCase(xsdType.charAt(0)) + xsdType.substring(1);
            OntProperty valueProp = expressModel.getOntProperty(expressNS + "has" + xsdTypeCAP);

            // Adding Content only if found
            for (int i = 0; i < reslist.size(); i++) {
                Resource r = reslist.get(i);
                String literalString = listelements.get(i);
                String key = valueProp.toString() + ":" + xsdType + ":" + literalString;
                Resource r2 = propertyResourceMap.get(key);
                if (r2 == null) {
                    r2 = ResourceFactory.createResource(baseURI + listrange.getLocalName() + "_" + IDcounter);
                    ttlWriter.triple(new Triple(r2.asNode(), RDF.type.asNode(), listrange.asNode()));
                    IDcounter++;
                    propertyResourceMap.put(key, r2);
                    addLiteralToResource(r2, valueProp, xsdType, literalString);
                }
                ttlWriter.triple(new Triple(r.asNode(), listModel.getOntProperty(listNS + "hasContents").asNode(), r2.asNode()));
                if (myIfcReaderStream.logToFile)
                    myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + "-hasContents-" + " - " + r2.getLocalName() + "\r\n");

                if (i < listelements.size() - 1) {
                    ttlWriter.triple(new Triple(r.asNode(), listModel.getOntProperty(listNS + "hasNext").asNode(), reslist.get(i + 1).asNode()));
                    if (myIfcReaderStream.logToFile)
                        myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + "-hasNext-" + " - " + reslist.get(i + 1).getLocalName() + "\r\n");
                }
            }
        } else
            return;
    }

    // HELPER METHODS
    private String filterExtras(String txt) {
        StringBuffer sb = new StringBuffer();
        for (int n = 0; n < txt.length(); n++) {
            char ch = txt.charAt(n);
            switch (ch) {
                case '\'':
                    break;
                case '=':
                    break;
                default:
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private String filterPoints(String txt) {
        StringBuffer sb = new StringBuffer();
        for (int n = 0; n < txt.length(); n++) {
            char ch = txt.charAt(n);
            switch (ch) {
                case '.':
                    break;
                default:
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private Long toLong(String txt) {
        try {
            return Long.valueOf(txt);
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private void addLiteral(Resource r, OntProperty valueProp, Literal l) {
        ttlWriter.triple(new Triple(r.asNode(), valueProp.asNode(), l.asNode()));
    }

    private void addProperty(Resource r, OntProperty valueProp, Resource r1) {
        ttlWriter.triple(new Triple(r.asNode(), valueProp.asNode(), r1.asNode()));
    }

    private String getXSDTypeFromRange(OntResource range) {
        if (range.asClass().getURI().equalsIgnoreCase(expressNS + "STRING") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "STRING")))
            return "string";
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "REAL") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "REAL")))
            return "double";
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "INTEGER") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "INTEGER")))
            return "integer";
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "BINARY") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "BINARY")))
            return "hexBinary";
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "BOOLEAN") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "BOOLEAN")))
            return "boolean";
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "LOGICAL") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "LOGICAL")))
            return "logical";
        else if (range.asClass().getURI().equalsIgnoreCase(expressNS + "NUMBER") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "NUMBER")))
            return "double";
        else
            return null;
    }

    private String getXSDTypeFromRangeExpensiveMethod(OntResource range) {
        ExtendedIterator<OntClass> iter = range.asClass().listSuperClasses();
        while (iter.hasNext()) {
            OntClass superc = iter.next();
            if (!superc.isAnon()) {
                String type = getXSDTypeFromRange(superc);
                if (type != null)
                    return type;
            }
        }
        return null;
    }

    private Resource getResource(String uri, OntResource rclass) {
        Resource r = resourceMap.get(uri);
        if (r == null) {
            r = ResourceFactory.createResource(uri);
            resourceMap.put(uri, r);
            try {
                ttlWriter.triple(new Triple(r.asNode(), RDF.type.asNode(), rclass.asNode()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return r;
    }

}
