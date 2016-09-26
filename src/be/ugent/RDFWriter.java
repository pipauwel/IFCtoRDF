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
import org.openbimstandards.ifcowl.vo.EntityVO;
import org.openbimstandards.ifcowl.vo.IFCVO;
import org.openbimstandards.ifcowl.vo.TypeVO;

import fi.ni.rdf.Namespace;

public class RDFWriter {

	//input variables
	private final String baseURI;
	private final String ontNS;
	private static final String expressURI = "https://w3id.org/express";
	private static final String expressNS = expressURI+"#";
	private static final String listURI = "https://w3id.org/list";
	private static final String listNS = listURI+"#";
	
	//EXPRESS basis
	private final Map<String, EntityVO> ent;
	private final Map<String, TypeVO> typ;
	
	//conversion variables
	private int IDcounter = 0;	
	private Map<Long, IFCVO> linemap = new HashMap<Long, IFCVO>();
		
	private StreamRDF ttl_writer;
	private InputStream inputStream;
	private final OntModel ontModel;
	private final OntModel expressModel;
	private final OntModel listModel;
	
	private IfcSpfReader myIfcReaderStream;

	//for removing duplicates in line entries
	private Map<String,Resource> listOfUniqueResources= new HashMap<String,Resource>();
	private Map<Long,Long> listOfDuplicateLineEntries= new HashMap<Long,Long>();
		
	// Taking care of avoiding duplicate resources
	private Map<String,Resource> property_resource_map=new HashMap<String,Resource>();  
	private Map<String,Resource> resource_map=new HashMap<String,Resource>();  
	
	public RDFWriter(OntModel ontModel, OntModel expressModel, OntModel listModel, InputStream inputStream, String baseURI, Map<String, EntityVO> ent, Map<String, TypeVO> typ, String ontURI){
		this.ontModel = ontModel;
		this.expressModel = expressModel;
		this.listModel = listModel;
		this.inputStream = inputStream;
		this.baseURI = baseURI;
		this.ent = ent;
		this.typ = typ;
		this.ontNS = ontURI + "#";
	}
	
	public void setIfcReader(IfcSpfReader r){
		this.myIfcReaderStream = r;
	}
	
	public void parseModel2Stream(OutputStream out) throws IOException{
		ttl_writer = StreamRDFWriter.getWriterStream(out, RDFFormat.TURTLE_BLOCKS) ;
		ttl_writer.base(baseURI);
		ttl_writer.prefix("ifcowl", ontNS);
		ttl_writer.prefix("inst", baseURI);
		ttl_writer.prefix("list", listNS);
		ttl_writer.prefix("express", expressNS);		
		ttl_writer.prefix("rdf", Namespace.RDF);		
		ttl_writer.prefix("xsd", Namespace.XSD);		
		ttl_writer.prefix("owl", Namespace.OWL);
		ttl_writer.start();
		
		ttl_writer.triple(new Triple(NodeFactory.createURI(baseURI), RDF.type.asNode(), OWL.Ontology.asNode()));
		ttl_writer.triple(new Triple(NodeFactory.createURI(baseURI), OWL.imports.asNode(), NodeFactory.createURI(ontNS)));
		
		//Read the whole file into a linemap Map object
		readModel();

		System.out.println("model parsed");

		resolveDuplicates();

		//map entries of the linemap Map object to the ontology Model and make new instances in the model	
		mapEntries();

		System.out.println("entries mapped, now creating instances");
		createInstances();
		
		// Save memory
		linemap.clear();
		linemap = null;
		
		ttl_writer.finish();
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
							//the whole IFC gets parsed, and everything ends up as IFCVO objects in the Map<Long, IFCVO> linemap variable
							parse_IFC_LineStatement(sb.toString().substring(1));
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
	
	private void parse_IFC_LineStatement(String line) {
		IFCVO ifcvo = new IFCVO();
		ifcvo.setFullLineAfterNum(line.substring(line.indexOf("=")+1));
		int state = 0;
		StringBuffer sb = new StringBuffer();
		int cl_count = 0;
		LinkedList<Object> current = ifcvo.getObjectList();
		Stack<LinkedList<Object>> list_stack = new Stack<LinkedList<Object>>();
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			switch (state) {
			case 0:
				if (ch == '=') {
					ifcvo.setLine_num(toLong(sb.toString()));
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
					list_stack.push(current);
					LinkedList<Object> tmp = new LinkedList<Object>();
					if (sb.toString().trim().length() > 0)
						current.add(sb.toString().trim());
					sb.setLength(0);
					current.add(tmp);
					current = tmp;
					cl_count++;
					// sb.append(ch);
				} else if (ch == ')') {
					if (cl_count == 0) {
						if (sb.toString().trim().length() > 0)
							current.add(sb.toString().trim());
						sb.setLength(0);
						state = Integer.MAX_VALUE; // line is done
						continue;
					} else {
						if (sb.toString().trim().length() > 0)
							current.add(sb.toString().trim());
						sb.setLength(0);
						cl_count--;
						current = list_stack.pop();
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
		linemap.put(ifcvo.getLine_num(), ifcvo);
		IDcounter++;
	}
	
	private void resolveDuplicates() throws IOException{
		Map<String,IFCVO> listOfUniqueResources= new HashMap<String,IFCVO>();
		List<Long> entriesToRemove=new ArrayList<Long>();
		for (Map.Entry<Long, IFCVO> entry : linemap.entrySet()) {
			IFCVO vo = entry.getValue();
			String t = vo.getFullLineAfterNum();
			if(!listOfUniqueResources.containsKey(t))				
				listOfUniqueResources.put(t, vo);
			else{
				//found duplicate
				entriesToRemove.add(entry.getKey());//linemap.remove(entry.getKey());
				listOfDuplicateLineEntries.put(vo.getLine_num(), listOfUniqueResources.get(t).getLine_num());
			}
		}
		if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("found and removed " + listOfDuplicateLineEntries.size() +" duplicates! \r\n");
		for(Long x : entriesToRemove){
			linemap.remove(x);
		}
	}
	
	private void mapEntries(){
		for (Map.Entry<Long, IFCVO> entry : linemap.entrySet()) {
			IFCVO vo = entry.getValue();
			
			//mapping properties to IFCVOs
			for (int i = 0; i < vo.getObjectList().size(); i++) {
				Object o = vo.getObjectList().get(i);
				if (String.class.isInstance(o)) {
					String s = (String) o;
					if (s.length() < 1)
						continue;
					if (s.charAt(0) == '#') {
						Object or = null;
						if(listOfDuplicateLineEntries.containsKey(toLong(s.substring(1))))
							or=linemap.get(listOfDuplicateLineEntries.get(toLong(s.substring(1))));
						else
							or = linemap.get(toLong(s.substring(1)));
						vo.getObjectList().set(i, or);
					}
				}
				if (LinkedList.class.isInstance(o)) {
					@SuppressWarnings("unchecked")
					LinkedList<Object> tmp_list = (LinkedList<Object>) o;
					
					for (int j = 0; j < tmp_list.size(); j++) {
						Object o1 = tmp_list.get(j);
						if (String.class.isInstance(o1)) {
							String s = (String) o1;
							if (s.length() < 1)
								continue;
							if (s.charAt(0) == '#') {
								Object or = null;
								if(listOfDuplicateLineEntries.containsKey(toLong(s.substring(1))))
									or=linemap.get(listOfDuplicateLineEntries.get(toLong(s.substring(1))));
								else
									or = linemap.get(toLong(s.substring(1)));
								if (or == null) {
									System.err
											.println("Reference to non-existing line in the IFC file.");
									tmp_list.set(j, "-");
								} else
									tmp_list.set(j, or);
							}
						} else if (LinkedList.class.isInstance(o1)) {
							@SuppressWarnings("unchecked")
							LinkedList<Object> tmp2_list = (LinkedList<Object>) o1;
							for (int j2 = 0; j2 < tmp2_list.size(); j2++) {
								Object o2 = tmp2_list.get(j2);
								if (String.class.isInstance(o2)) {
									String s = (String) o2;
									if (s.length() < 1)
										continue;
									if (s.charAt(0) == '#') {
										Object or = null;
										if(listOfDuplicateLineEntries.containsKey(toLong(s.substring(1))))
											or=linemap.get(listOfDuplicateLineEntries.get(toLong(s.substring(1))));
										else
											or = linemap.get(toLong(s.substring(1)));
										if (or == null) {
											System.err
													.println("Reference to non-existing line in the IFC file.");
											tmp_list.set(j, "-");
										} else
											tmp_list.set(j, or);
									}
								}
							}
						}
					}
				}
			}
		}
	}
	
	private void createInstances() throws IOException{		
		int i = 0;
		for (Map.Entry<Long, IFCVO> entry : linemap.entrySet()) {
			IFCVO ifc_lineEntry = entry.getValue();		
			String typeName = "";
			if(ent.containsKey(ifc_lineEntry.getName()))
				typeName = ent.get(ifc_lineEntry.getName()).getName();
			else if(typ.containsKey(ifc_lineEntry.getName()))
				typeName = typ.get(ifc_lineEntry.getName()).getName();				
			
			OntClass cl = ontModel.getOntClass(ontNS + typeName);
				
			Resource r = getResource(baseURI + typeName + "_" + ifc_lineEntry.getLine_num(),cl);
			listOfUniqueResources.put(ifc_lineEntry.getFullLineAfterNum(),r);
				
			if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("-------------------------------" + "\r\n");
			if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write(r.getLocalName() + "\r\n");
			if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("-------------------------------" + "\r\n");
					
			fillProperties(ifc_lineEntry, r, cl);
			i++;
		}
		// The map is used only to avoid duplicates.
		// So, it can be cleared here
		property_resource_map.clear();
	}
	
	TypeVO typeremembrance = null;
	
	private void fillProperties(IFCVO ifc_lineEntry,Resource r, OntClass cl) throws IOException {		
	
		EntityVO evo = ent.get(ExpressReader.formatClassName(ifc_lineEntry.getName()));
		TypeVO tvo = typ.get(ExpressReader.formatClassName(ifc_lineEntry.getName()));
		
		if(tvo==null && evo==null){
			System.err.println("Type nor entity exists: " + ifc_lineEntry.getName());
		}
		
		if (evo == null && tvo!=null){
//			System.err.println("Entity does not exist: " + ifc_lineEntry.getName());	
			final String subject = tvo.getName() + "_" + ifc_lineEntry.getLine_num();
			
			typeremembrance = null;
			int attribute_pointer = 0;
			for (Object o: ifc_lineEntry.getObjectList()) {
				
				if (String.class.isInstance(o)) {
					System.out.println("WARNING: unhandled type property found.");
//					attribute_pointer = fillProperties_handleStringObject(r, tvo,
//							subject, attribute_pointer, o);
				} else if (IFCVO.class.isInstance(o)) {
					System.out.println("WARNING: unhandled type property found.");
//					attribute_pointer = fillProperties_handleIFC_Object(r,
//							tvo, attribute_pointer, o);
				} else if (LinkedList.class.isInstance(o)) {
					fillProperties_handleListObject(r, tvo, o);
				}	
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.flush();
			}		
		}
		
		if (tvo == null && evo !=null){
//			System.err.println("Type does not exist: " + ifc_lineEntry.getName());	
			final String subject = evo.getName() + "_" + ifc_lineEntry.getLine_num();
			
			typeremembrance = null;
			int attribute_pointer = 0;
			for (Object o: ifc_lineEntry.getObjectList()) {
				
				if (String.class.isInstance(o)) {
					attribute_pointer = fillProperties_handleStringObject(r, evo,
							subject, attribute_pointer, o);
				} else if (IFCVO.class.isInstance(o)) {
					attribute_pointer = fillProperties_handleIFC_Object(r,
							evo, attribute_pointer, o);
				} else if (LinkedList.class.isInstance(o)) {
					attribute_pointer = fillProperties_handleListObject(r, evo,
							attribute_pointer, o);
				}	
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.flush();
			}
		}
	
		
		if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.flush();
	}

	private int fillProperties_handleStringObject(Resource r, EntityVO evo,
			String subject, int attribute_pointer, Object o) throws IOException {
		if (!((String) o).equals("$") && !((String) o).equals("*")) { 
	
			if (typ.get(ExpressReader.formatClassName((String) o)) == null) {
				if ((evo != null)
						&& (evo.getDerived_attribute_list() != null)
						&& (evo.getDerived_attribute_list().size() > attribute_pointer)) {
	
					final String propURI = ontNS + evo.getDerived_attribute_list().get(attribute_pointer).getLowerCaseName();
					final String literalString = filter_extras((String) o);					
	
					OntProperty p = ontModel.getOntProperty(propURI);
					OntResource range = p.getRange();
					if(range.isClass()){
//						Iterator<OntClass> x = range.asClass().listSuperClasses();
//						while(x.hasNext()){
//							System.out.println("found superclass : " + x.next().getLocalName());
//						} 
						OntClass c = expressModel.getOntClass(expressNS + "ENUMERATION");
						if(range.asClass().hasSuperClass(c)){
							addEnumProperty(r,p,range,literalString);
						}				
						//Check for SELECT
						else if(range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "SELECT"))){
							if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("1 - WARNING TODO: found SELECT property: " + p + " - " + range.getLocalName() + " - " + literalString + "\r\n");
						}									
						else if(range.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))){
							if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("1a - WARNING TODO: found LIST property: " + subject + " -- " + p + " - " + range.getLocalName() + " - " + literalString + "\r\n");
						}
						else {	
							System.out.println("getXSDTypeFromRange(range) : " + range);
							String xsdType = getXSDTypeFromRange(range);
							if(xsdType == null)
							{
								xsdType = getXSDTypeFromRangeExpensiveMethod(range);
							}
							if(xsdType!=null){
								String xsdTypeCAP = Character.toUpperCase(xsdType.charAt(0)) + xsdType.substring(1);
								OntProperty valueProp = expressModel.getOntProperty(expressNS + "has" + xsdTypeCAP);
								
								// Create only when needed...
								String key=valueProp.toString()+":"+xsdType+":"+literalString;
								Resource r1 = property_resource_map.get(key);
								if(r1==null)
								{
									r1 = ResourceFactory.createResource(baseURI + range.getLocalName() + "_" + IDcounter);
									ttl_writer.triple(new Triple(r1.asNode(), RDF.type.asNode(), range.asNode()));
									if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("created resource: " + r1.getLocalName() + "\r\n");
									IDcounter++;
									property_resource_map.put(key,r1);
									addLiteralToResource(r1,valueProp,xsdType,literalString);
								}
								ttl_writer.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
								if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName() + "\r\n");
							}
							else{
								if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("1b - WARNING TODO: this should not happen for: " + p + " - " + range.getURI() + " - " + literalString + "\r\n");
							}
						}									
					}
					else {
						if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("5 - WARNING: found other kind of property: " + p + " - " + range.getLocalName() + "\r\n");										
					}
				}
				attribute_pointer++;
			}
			else{
				typeremembrance = typ.get(ExpressReader.formatClassName((String) o));
			}
		} else
			attribute_pointer++;
		return attribute_pointer;
	}

	private int fillProperties_handleIFC_Object(Resource r, EntityVO evo,
			int attribute_pointer, Object o) throws IOException {
		if ((evo != null)
				&& (evo.getDerived_attribute_list() != null)
				&& (evo.getDerived_attribute_list().size() > attribute_pointer)) {

			final String propURI = ontNS + evo.getDerived_attribute_list().get(attribute_pointer).getLowerCaseName();
			EntityVO evorange = ent.get(ExpressReader.formatClassName(((IFCVO)o).getName()));

			OntProperty p = ontModel.getOntProperty(propURI);
			OntResource rclass = ontModel.getOntResource(ontNS + evorange.getName());

			Resource r1 = getResource(baseURI + evorange.getName() + "_" + ((IFCVO) o).getLine_num(),rclass);
			ttl_writer.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));		
			if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName() + "\r\n");
		} 
		attribute_pointer++;
		return attribute_pointer;
	}

	private int fillProperties_handleListObject(Resource r, EntityVO evo,
			int attribute_pointer, Object o) throws IOException {	
		
		@SuppressWarnings("unchecked")
		final LinkedList<Object> tmp_list = (LinkedList<Object>) o;
		LinkedList<String> literals=new LinkedList<String>();		
		LinkedList<Resource> listremembranceresources = new LinkedList<Resource>();
		
		//process list
		for (int j = 0; j < tmp_list.size(); j++) {
			Object o1 = tmp_list.get(j);
			if (String.class.isInstance(o1)) {
				if (typ.get(ExpressReader.formatClassName((String) o1)) != null && typeremembrance==null)
					typeremembrance = typ.get(ExpressReader.formatClassName((String) o1));	
				else
					literals.add(filter_extras((String) o1));				
			}
			if (IFCVO.class.isInstance(o1)) {
				if ((evo != null)
						&& (evo.getDerived_attribute_list() != null)
						&& (evo.getDerived_attribute_list().size() > attribute_pointer)) {

					String propURI = evo.getDerived_attribute_list().get(attribute_pointer).getLowerCaseName();
					OntProperty p = ontModel.getOntProperty(ontNS + propURI);
					OntResource typerange = p.getRange();

					if(typerange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))){
						//EXPRESS LISTs
						String listvaluepropURI = ontNS + typerange.getLocalName().substring(0, typerange.getLocalName().length()-5);	
						OntResource listrange = ontModel.getOntResource(listvaluepropURI);

						if(listrange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))){
							if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("6 - WARNING: Found unhandled ListOfList" + "\r\n");
						}
						else{
							fillClassInstanceList(tmp_list, typerange, p, r);
							j = tmp_list.size()-1;
						}
					}			
					else{
						//EXPRESS SETs
						EntityVO evorange = ent.get(ExpressReader.formatClassName(((IFCVO)o1).getName()));								
						OntResource rclass = ontModel.getOntResource(ontNS + evorange.getName());

						Resource r1 = getResource(baseURI + evorange.getName() + "_" + ((IFCVO) o1).getLine_num(),rclass);
						ttl_writer.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));	
						if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName() + "\r\n");		
					}
				}
			}
			if(LinkedList.class.isInstance(o1)){
				if(typeremembrance!=null){
					LinkedList<Object> tmp_list_inlist = (LinkedList<Object>) o1;
					for(int jj = 0; jj<tmp_list_inlist.size(); jj++){
						Object o2 = tmp_list_inlist.get(jj);
						if(String.class.isInstance(o2)){
							literals.add(filter_extras((String) o2));
						}
						else if(LinkedList.class.isInstance(o2)){
							//this happens only for types that are equivalent to lists (e.g. IfcLineIndex in IFC4_ADD1)
							// in this case, the elements of the list should be treated as new instances that are equivalent to the correct lists
							LinkedList<Object> tmp_list_inlist_inlist = (LinkedList<Object>) o2;
							LinkedList<String> newList = new LinkedList<String>();
							for(int jjj = 0; jjj<tmp_list_inlist_inlist.size(); jjj++){
								Object o3 = tmp_list_inlist_inlist.get(jjj);
								if(String.class.isInstance(o3)){
									literals.add(filter_extras((String) o3));
								}
							}
							
							//exception. when a list points to a number of linked lists, it could be that there are multiple different entities are referenced
							//example: #308= IFCINDEXEDPOLYCURVE(#309,(IFCLINEINDEX((1,2)),IFCARCINDEX((2,3,4)),IFCLINEINDEX((4,5)),IFCARCINDEX((5,6,7))),.F.);
							//in this case, it is better to immediately print all relevant entities and properties for each case (e.g. IFCLINEINDEX((1,2))),
							//and reset typeremembrance for the next case (e.g. IFCARCINDEX((4,5))).								
	
							if ((evo != null)
									&& (evo.getDerived_attribute_list() != null)
									&& (evo.getDerived_attribute_list().size() > attribute_pointer)) {	
	
								OntClass cl = ontModel.getOntClass(ontNS + typeremembrance.getName());
								Resource r1 = getResource(baseURI + typeremembrance.getName() + "_" + IDcounter, cl);
								IDcounter++;
								
								String[] primtypeArr = typeremembrance.getPrimarytype().split(" ");
								String primType = primtypeArr[primtypeArr.length-1].replace(";", "") + "_" + primtypeArr[0].substring(0,1).toUpperCase() + primtypeArr[0].substring(1).toLowerCase();
								String typeURI = ontNS + primType;
								OntResource range = ontModel.getOntResource(typeURI);	
								addDirectRegularListProperty(r1, range, literals);	
								
								//put relevant top list items in a list, which can then be parsed at the end of this method
								listremembranceresources.add(r1);				
							}
							
							typeremembrance = null;
							literals.clear();
						}
					}
				}
				else{
					LinkedList<Object> tmp_list_inlist = (LinkedList<Object>) o1;
					for(int jj = 0; jj<tmp_list_inlist.size(); jj++){
						Object o2 = tmp_list_inlist.get(jj);
						if(String.class.isInstance(o2)){
							literals.add(filter_extras((String) o2));
						}
					}
					if ((evo != null)
							&& (evo.getDerived_attribute_list() != null)
							&& (evo.getDerived_attribute_list().size() > attribute_pointer)) {	
						
						String propURI = ontNS + evo.getDerived_attribute_list().get(attribute_pointer).getLowerCaseName();
						OntProperty p = ontModel.getOntProperty(propURI);	
						OntClass typerange = p.getRange().asClass();

						if(typerange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))){
							//Should always be the case
							String listvaluepropURI = typerange.getLocalName().substring(0, typerange.getLocalName().length()-5);	
							OntResource listrange = ontModel.getOntResource(ontNS + listvaluepropURI);
							Resource r1 = getResource(baseURI + listvaluepropURI + "_" + IDcounter, listrange);
							IDcounter++;
							addDirectRegularListProperty(r1, listrange, literals);	
							listremembranceresources.add(r1);		
						}
					}
					
					literals.clear();
				}
			}
		}

		//interpret parse
		if (literals.size() > 0) {
			if(typeremembrance != null){
				if ((evo != null)
						&& (evo.getDerived_attribute_list() != null)
						&& (evo.getDerived_attribute_list().size() > attribute_pointer)) {				

					String propURI = ontNS + evo.getDerived_attribute_list().get(attribute_pointer).getLowerCaseName();
					OntProperty p = ontModel.getOntProperty(propURI);

					addSinglePropertyFromTypeRemembrance(r, p, literals.getFirst(), typeremembrance);
				}
				typeremembrance = null;
			}
			else if ((evo != null)
					&& (evo.getDerived_attribute_list() != null)
					&& (evo.getDerived_attribute_list().size() > attribute_pointer)) {						
				String propURI = ontNS + evo.getDerived_attribute_list().get(attribute_pointer).getLowerCaseName();
				OntProperty p = ontModel.getOntProperty(propURI);				
				addRegularListProperty(r, p, literals);
			}
		}
		if(listremembranceresources.size() > 0){
			if ((evo != null)
					&& (evo.getDerived_attribute_list() != null)
					&& (evo.getDerived_attribute_list().size() > attribute_pointer)) {						
				String propURI = ontNS + evo.getDerived_attribute_list().get(attribute_pointer).getLowerCaseName();
				OntProperty p = ontModel.getOntProperty(propURI);				
				addListPropertyToGivenEntities(r, p, listremembranceresources);
			}
		}

		attribute_pointer++;
		return attribute_pointer;
	}

	private void fillProperties_handleListObject(Resource r, TypeVO tvo,
			Object o) throws IOException {	
		
		@SuppressWarnings("unchecked")
		final LinkedList<Object> tmp_list = (LinkedList<Object>) o;
		LinkedList<String> literals=new LinkedList<String>();		
		
		//process list
		for (int j = 0; j < tmp_list.size(); j++) {
			Object o1 = tmp_list.get(j);
			if (String.class.isInstance(o1)) {
				if (typ.get(ExpressReader.formatClassName((String) o1)) != null && typeremembrance==null)
					typeremembrance = typ.get(ExpressReader.formatClassName((String) o1));	
				else
					literals.add(filter_extras((String) o1));				
			}
			if (IFCVO.class.isInstance(o1)) {
				if ((tvo != null)) {
					if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("TODO 16: found TYPE that is equivalent to a list if IFC entities - below is the code used when this happens for ENTITIES with a list of ENTITIES" + "\r\n");
					System.out.println("TODO 16: found TYPE that is equivalent to a list if IFC entities - below is the code used when this happens for ENTITIES with a list of ENTITIES");
//					String propURI = tvo.evo.getDerived_attribute_list().get(attribute_pointer).getLowerCaseName();
//					OntProperty p = ontModel.getOntProperty(ontNS + propURI);
//					OntResource typerange = p.getRange();
//
//					if(typerange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))){
//						//EXPRESS LISTs
//						String listvaluepropURI = ontNS + typerange.getLocalName().substring(0, typerange.getLocalName().length()-5);	
//						OntResource listrange = ontModel.getOntResource(listvaluepropURI);
//
//						if(listrange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))){
//							if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("6 - WARNING: Found unhandled ListOfList" + "\r\n");
//						}
//						else{
//							fillClassInstanceList(tmp_list, typerange, p, r);
//							j = tmp_list.size()-1;
//						}
//					}			
//					else{
//						//EXPRESS SETs
//						EntityVO evorange = ent.get(ExpressReader.formatClassName(((IFCVO)o1).getName()));								
//						OntResource rclass = ontModel.getOntResource(ontNS + evorange.getName());
//
//						Resource r1 = getResource(baseURI + evorange.getName() + "_" + ((IFCVO) o1).getLine_num(),rclass);
//						ttl_writer.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));	
//						if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName() + "\r\n");		
//					}
				}
			}
			if(LinkedList.class.isInstance(o1) && typeremembrance != null){
				LinkedList<Object> tmp_list_inlist = (LinkedList<Object>) o1;
				for(int jj = 0; jj<tmp_list_inlist.size(); jj++){
					Object o2 = tmp_list_inlist.get(jj);
					if(String.class.isInstance(o2)){
						literals.add(filter_extras((String) o2));
					}
				}
			}
		}

		//interpret parse
		if (literals.size() > 0) {
			if(typeremembrance != null){
				if ((tvo != null)){
//					&& (tvo.getDerived_attribute_list() != null)
//					&& (tvo.getDerived_attribute_list().size() > attribute_pointer)) {	
					if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("WARNING: this part of the code has not been checked - it can't be correct" + "\r\n");
					System.out.println("WARNING: this part of the code has not been checked - it can't be correct");

					String[] primtypeArr = tvo.getPrimarytype().split(" ");
					String primType = primtypeArr[primtypeArr.length-1].replace(";", "") + "_" + primtypeArr[0].substring(0,1).toUpperCase() + primtypeArr[0].substring(1).toLowerCase();
					String typeURI = ontNS + primType; 
					OntResource range = ontModel.getOntResource(typeURI);				
					addDirectRegularListProperty(r, range, literals);			

//					String propURI = ontNS + tvo.getName();//.getDerived_attribute_list().get(attribute_pointer).getLowerCaseName();
//					OntProperty p = ontModel.getOntProperty(propURI);
//					addSinglePropertyFromTypeRemembrance(r, p, literals.getFirst(), typeremembrance);
				}
				typeremembrance = null;
			}
			else if ((tvo != null)){
				String[] primtypeArr = tvo.getPrimarytype().split(" ");
				String primType = primtypeArr[primtypeArr.length-1].replace(";", "") + "_" + primtypeArr[0].substring(0,1).toUpperCase() + primtypeArr[0].substring(1).toLowerCase();
				String typeURI = ontNS + primType; 
				OntResource range = ontModel.getOntResource(typeURI);				
				addDirectRegularListProperty(r, range, literals);
			}
		}
	}
		
	private void addSinglePropertyFromTypeRemembrance(Resource r, OntProperty p, String literalString, TypeVO typeremembrance) throws IOException{				
			OntResource range = ontModel.getOntResource(ontNS + typeremembrance.getName());
			
			if(range.isClass()){
				//Check for ENUM
				if(range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "ENUMERATION"))){
					addEnumProperty(r,p,range,literalString);									
				}								
				//Check for SELECT
				else if(range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "SELECT"))){
					if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("9 - WARNING TODO: found SELECT property: " + p + " - " + range.getLocalName() + " - " + literalString + "\r\n");
				}	
				else {
					String xsdType = getXSDTypeFromRange(range);
					if(xsdType == null)
						xsdType = getXSDTypeFromRangeExpensiveMethod(range);
					if(xsdType!=null){
						String xsdTypeCAP = Character.toUpperCase(xsdType.charAt(0)) + xsdType.substring(1);
						OntProperty valueProp = expressModel.getOntProperty(expressNS + "has" + xsdTypeCAP);
						String key=valueProp.toString()+":"+xsdType+":"+literalString;
						
						Resource r1 = property_resource_map.get(key);
						if(r1==null)
						{
							r1 = ResourceFactory.createResource(baseURI + typeremembrance.getName() + "_" + IDcounter);
							ttl_writer.triple(new Triple(r1.asNode(), RDF.type.asNode(), range.asNode()));		
							IDcounter++;
							property_resource_map.put(key,r1);
							addLiteralToResource(r1,valueProp,xsdType,literalString);
						}
						ttl_writer.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
						if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName() + "\r\n");		
					}
				}									
			}
			else {
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("12 - WARNING: found other kind of property: " + p + " - " + range.getLocalName() + "\r\n");										
			}
	}
		
	private void addEnumProperty(Resource r, Property p, OntResource range, String literalString) throws IOException{
		for (ExtendedIterator<? extends OntResource> instances = range.asClass().listInstances(); instances.hasNext(); ) {
            OntResource rangeInstance = instances.next();
            if( rangeInstance.getProperty(RDFS.label).getString().equalsIgnoreCase(filter_points(literalString))){
            	ttl_writer.triple(new Triple(r.asNode(), p.asNode(),  rangeInstance.asNode()));					            	
            	if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added ENUM statement " + r.getLocalName() + " - " + p.getLocalName() + " - " + rangeInstance.getLocalName() + "\r\n");
            	break;
            }
		}
	}
	
	private void addLiteralToResource(Resource r1, OntProperty valueProp, String xsdType, String literalString) throws IOException{
		if(xsdType.equalsIgnoreCase("integer"))
			addLiteral(r1,valueProp, ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDinteger));	
		else if(xsdType.equalsIgnoreCase("double"))
			addLiteral(r1,valueProp, ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDdouble));	
		else if(xsdType.equalsIgnoreCase("hexBinary"))
			addLiteral(r1,valueProp, ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDhexBinary));	
		else if(xsdType.equalsIgnoreCase("boolean")){
			if(literalString.equalsIgnoreCase(".F."))
				addLiteral(r1,valueProp, ResourceFactory.createTypedLiteral("false", XSDDatatype.XSDboolean));	
			else if(literalString.equalsIgnoreCase(".T."))
					addLiteral(r1,valueProp, ResourceFactory.createTypedLiteral("true", XSDDatatype.XSDboolean));
			else
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("WARNING: found odd boolean value: " + literalString + "\r\n");
		}
		else if(xsdType.equalsIgnoreCase("logical")){
			if(literalString.equalsIgnoreCase(".F."))
				addProperty(r1,valueProp, expressModel.getResource(expressNS + "FALSE"));
			else if(literalString.equalsIgnoreCase(".T."))
				addProperty(r1,valueProp, expressModel.getResource(expressNS + "TRUE"));
			else if(literalString.equalsIgnoreCase(".U."))
				addProperty(r1,valueProp, expressModel.getResource(expressNS + "UNKNOWN"));
			else
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("WARNING: found odd logical value: " + literalString + "\r\n");
		}
		else if(xsdType.equalsIgnoreCase("string"))
			addLiteral(r1,valueProp,ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDstring));	
		else
			addLiteral(r1,valueProp,ResourceFactory.createTypedLiteral(literalString));
		
		if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added literal: " + r1.getLocalName() + " - " + valueProp + " - " + literalString + "\r\n");
	}
	
	//LIST HANDLING
	private void addDirectRegularListProperty(Resource r, OntResource range, List<String> el) throws IOException{		
		//OntResource range = p.getRange();
		if(range.isClass()){
			OntResource listrange = getListContentType(range.asClass());
			
			if(listrange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))){
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("14 - WARNING: Found unhandled ListOfList" + "\r\n");
			}	
			else{
				List<Resource> reslist = new ArrayList<Resource>();
				//createrequirednumberofresources
				for(int ii = 0; ii<el.size();ii++){	
					if(ii==0)
						reslist.add(r);
					else{
						Resource r1 = getResource(baseURI + range.getLocalName() + "_" + IDcounter, range);
						reslist.add(r1);
						IDcounter++;
					}
				}	
				//bindtheproperties
				addListInstanceProperties(reslist,el,listrange);	
			}
		}
	}
	
	private void addRegularListProperty(Resource r, OntProperty p, List<String> el) throws IOException{		
		OntResource range = p.getRange();
		if(range.isClass()){
			OntResource listrange = getListContentType(range.asClass());
			
			if(listrange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))){
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("14 - WARNING: Found unhandled ListOfList" + "\r\n");
			}	
			else{
				List<Resource> reslist = new ArrayList<Resource>();
				//createrequirednumberofresources
				for(int ii = 0; ii<el.size();ii++){	
					Resource r1 = getResource(baseURI + range.getLocalName() + "_" + IDcounter, range);
					reslist.add(r1);
					IDcounter++;
					if(ii==0){
						ttl_writer.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
						if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName() + "\r\n");
					}
				}	
				//bindtheproperties
				addListInstanceProperties(reslist,el,listrange);	
			}
		}
	}
	
	private void addListPropertyToGivenEntities(Resource r, OntProperty p, List<Resource> el) throws IOException{
		OntResource range = p.getRange();
		if(range.isClass()){
			OntResource listrange = getListContentType(range.asClass());
			
			if(listrange.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))){
				System.out.println("handling list of list, but it is actually the same thing as usual");
				listrange = range;
			}
				for(int i = 0; i<el.size();i++){	
					Resource r1 = el.get(i);
					Resource r2 = ResourceFactory.createResource(baseURI + listrange.getLocalName() + "_" + IDcounter);
					ttl_writer.triple(new Triple(r2.asNode(), RDF.type.asNode(), listrange.asNode()));	
					if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r2.getLocalName() + " - rdf:type - " + listrange.getLocalName() + "\r\n");					
					IDcounter++;
					Resource r3 = ResourceFactory.createResource(baseURI + listrange.getLocalName() + "_" + IDcounter);

					if(i==0){
						ttl_writer.triple(new Triple(r.asNode(), p.asNode(), r2.asNode()));	
						if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r2.getLocalName() + "\r\n");
					}
					ttl_writer.triple(new Triple(r2.asNode(), listModel.getOntProperty(listNS + "hasContents").asNode(), r1.asNode()));
					if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r2.getLocalName() + " - " + "-hasContents-" + " - " + r1.getLocalName() + "\r\n");

					if(i<el.size()-1){								
						ttl_writer.triple(new Triple(r2.asNode(), listModel.getOntProperty(listNS + "hasNext").asNode(), r3.asNode()));
						if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r2.getLocalName() + " - " + "-hasNext-" + " - " + r3.getLocalName() + "\r\n");
					}	
				}
		}
	}
	
	private List<String> getListElements(String literalString) throws IOException{
		String[] elements = literalString.split("_, ");
		List<String> el = new ArrayList<String>();
		for(String element : elements){
			if(element.startsWith("_") && element.endsWith("_"))
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("WARNING getListElements(): Found list of enumerations" + "\r\n");
			if(element.contains("_")){
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("WARNING getListElements(): Found '_' in list elements" + "\r\n");
				element = element.replaceAll("_", "");
			}
			el.add(element);
		}
		return el;
	}
	
	private OntResource getListContentType(OntClass range) throws IOException{			
		if(range.asClass().getURI().equalsIgnoreCase(expressNS + "STRING_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "STRING_List")))
			return expressModel.getOntResource(expressNS + "STRING");
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "REAL_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "REAL_List")))
			return expressModel.getOntResource(expressNS + "REAL");
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "INTEGER_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "INTEGER_List")))
			return expressModel.getOntResource(expressNS + "INTEGER");
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "BINARY_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "BINARY_List")))
			return expressModel.getOntResource(expressNS + "BINARY");
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "BOOLEAN_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "BOOLEAN_List")))
			return expressModel.getOntResource(expressNS + "BOOLEAN");
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "LOGICAL_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "LOGICAL_List")))
			return expressModel.getOntResource(expressNS + "LOGICAL");
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "NUMBER_List") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "NUMBER_List")))
			return expressModel.getOntResource(expressNS + "NUMBER");
		else if(range.asClass().hasSuperClass(listModel.getOntClass(listNS + "OWLList"))){
			String listvaluepropURI = ontNS + range.getLocalName().substring(0, range.getLocalName().length()-5);
			return ontModel.getOntResource(listvaluepropURI);
		}
		else{
			if(myIfcReaderStream.logToFile) {
				myIfcReaderStream.bw.write("WARNING: did not find listcontenttype for : "+range.getLocalName() + "\r\n");
			}
			return null;
		}
	}
	
	private void fillClassInstanceList(LinkedList<Object> tmp_list, OntResource typerange, OntProperty p, Resource r) throws IOException{
		List<Resource> reslist = new ArrayList<Resource>();
		List<IFCVO> entlist = new ArrayList<IFCVO>();
		
		//createrequirednumberofresources
		for (int i = 0; i < tmp_list.size(); i++) {
			if (IFCVO.class.isInstance(tmp_list.get(i))) {
				Resource r1 = getResource(
						baseURI + typerange.getLocalName() + "_" + IDcounter,
						typerange);
				reslist.add(r1);
				IDcounter++;
				entlist.add((IFCVO)tmp_list.get(i));
				if (i == 0) {
					ttl_writer.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
				}
			}
		}	
		
		//bindtheproperties
		String listvaluepropURI = ontNS + typerange.getLocalName().substring(0, typerange.getLocalName().length()-5);	
		OntResource listrange = ontModel.getOntResource(listvaluepropURI);
		
		addClassInstanceListProperties(reslist,entlist,listrange);
	}
	
	private void addClassInstanceListProperties(List<Resource> reslist, List<IFCVO> entlist, OntResource listrange) throws IOException{
		OntProperty listp = listModel.getOntProperty(listNS + "hasContents");
		OntProperty isfollowed = listModel.getOntProperty(listNS + "hasNext");
		
		for(int i = 0; i<reslist.size();i++){	
			Resource r = reslist.get(i);					
			
			OntResource rclass = null;
			EntityVO evorange = ent.get(ExpressReader.formatClassName(entlist.get(i).getName()));	
			if(evorange==null){
				TypeVO typerange = typ.get(ExpressReader.formatClassName(entlist.get(i).getName()));
				rclass = ontModel.getOntResource(ontNS + typerange.getName());
				Resource r1 = getResource(baseURI + typerange.getName() + "_" + entlist.get(i).getLine_num(),rclass);
				ttl_writer.triple(new Triple(r.asNode(), listp.asNode(), r1.asNode()));
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("created property: " + r.getLocalName() + " - " + listp.getLocalName() + " - " + r1.getLocalName() + "\r\n");	
			}
			else{
				rclass = ontModel.getOntResource(ontNS + evorange.getName());
				Resource r1 = getResource(baseURI + evorange.getName() + "_" + entlist.get(i).getLine_num(),rclass);
				ttl_writer.triple(new Triple(r.asNode(), listp.asNode(), r1.asNode()));
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("created property: " + r.getLocalName() + " - " + listp.getLocalName() + " - " + r1.getLocalName() + "\r\n");	
			}
												
			if(i<reslist.size()-1){								
				ttl_writer.triple(new Triple(r.asNode(), isfollowed.asNode(), reslist.get(i+1).asNode()));
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("created property: " + r.getLocalName() + " - " + isfollowed.getLocalName() + " - " + reslist.get(i+1).getLocalName() + "\r\n");
			}	
		}
	}
	
	private void addListInstanceProperties(List<Resource> reslist, List<String> listelements, OntResource listrange) throws IOException{		
		//GetListType
		String xsdType = getXSDTypeFromRange(listrange);
		if(xsdType == null)
			xsdType = getXSDTypeFromRangeExpensiveMethod(listrange);
		if(xsdType!=null){
			String xsdTypeCAP = Character.toUpperCase(xsdType.charAt(0)) + xsdType.substring(1);
			OntProperty valueProp = expressModel.getOntProperty(expressNS + "has" + xsdTypeCAP);
			
			//Adding Content only if found
			for(int i = 0; i<reslist.size();i++){	
				Resource r = reslist.get(i);
				String literalString = listelements.get(i);
				String key=valueProp.toString()+":"+xsdType+":"+literalString;
				Resource r2 = property_resource_map.get(key);
				if(r2==null)
				{
					r2 = ResourceFactory.createResource(baseURI + listrange.getLocalName() + "_" + IDcounter);
					ttl_writer.triple(new Triple(r2.asNode(), RDF.type.asNode(), listrange.asNode()));						
					IDcounter++;
					property_resource_map.put(key,r2);
					addLiteralToResource(r2,valueProp,xsdType,literalString);
				}
				ttl_writer.triple(new Triple(r.asNode(), listModel.getOntProperty(listNS + "hasContents").asNode(), r2.asNode()));
				if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + "-hasContents-" + " - " + r2.getLocalName() + "\r\n");

				if(i<listelements.size()-1){								
					ttl_writer.triple(new Triple(r.asNode(), listModel.getOntProperty(listNS + "hasNext").asNode(), reslist.get(i+1).asNode()));
					if(myIfcReaderStream.logToFile) myIfcReaderStream.bw.write("added property: " + r.getLocalName() + " - " + "-hasNext-" + " - " + reslist.get(i+1).getLocalName() + "\r\n");
				}	
			}
		}	
		else
			return;
	}
	
	// HELPER METHODS
	private String filter_extras(String txt) {
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

	private String filter_points(String txt) {
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
	
	private void addLiteral(Resource r,OntProperty valueProp, Literal l)
	{
		ttl_writer.triple(new Triple(r.asNode(), valueProp.asNode(), l.asNode()));		
	}
	
	private void addProperty(Resource r,OntProperty valueProp, Resource r1)
	{
		ttl_writer.triple(new Triple(r.asNode(), valueProp.asNode(), r1.asNode()));		
	}
	
	private String getXSDTypeFromRange(OntResource range){		
		if(range.asClass().getURI().equalsIgnoreCase(expressNS + "STRING") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "STRING")))
			return "string";
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "REAL") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "REAL")))
			return "double";
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "INTEGER") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "INTEGER")))
			return "integer";
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "BINARY") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "BINARY")))
			return "hexBinary";
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "BOOLEAN") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "BOOLEAN")))
			return "boolean";
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "LOGICAL") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "LOGICAL")))
			return "logical";
		else if(range.asClass().getURI().equalsIgnoreCase(expressNS + "NUMBER") || range.asClass().hasSuperClass(expressModel.getOntClass(expressNS + "NUMBER")))
			return "double";
		else
			return null;
	}
	
	private String getXSDTypeFromRangeExpensiveMethod(OntResource range){
		ExtendedIterator<OntClass> iter = range.asClass().listSuperClasses();
		while (iter.hasNext()){
			OntClass superc = iter.next();
			if(!superc.isAnon()){
				String type = getXSDTypeFromRange(superc);
				if(type!=null)
					return type;
			}
		}
		return null;
	}
	
	private Resource getResource(String uri,OntResource rclass)
	{
		Resource r=resource_map.get(uri);
		if(r==null)
		{
		   r=ResourceFactory.createResource(uri);
		   resource_map.put(uri, r);
		   try
		   {
			   ttl_writer.triple(new Triple(r.asNode(), RDF.type.asNode(), rclass.asNode()));
		   }
		   catch(Exception e)
		   {
			   e.printStackTrace();
		   }
		}
		return r;
	}

}
