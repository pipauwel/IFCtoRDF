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
package be.ugent;

import be.ugent.progress.TaskProgressListener;
import be.ugent.progress.TaskProgressReporter;
import com.buildingsmart.tech.ifcowl.ExpressReader;
import com.buildingsmart.tech.ifcowl.vo.EntityVO;
import com.buildingsmart.tech.ifcowl.vo.IFCVO;
import com.buildingsmart.tech.ifcowl.vo.TypeVO;
import fi.ni.rdf.Namespace;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
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
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.riot.system.StreamRDFWriter;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class RDFWriter {

  // input variables
  private final String baseURI;
  private final String ontNS;
  private static final String EXPRESS_URI = "https://w3id.org/express";
  private static final String EXPRESS_NS = EXPRESS_URI + "#";
  private static final String LIST_URI = "https://w3id.org/list";
  private static final String LIST_NS = LIST_URI + "#";

  //data from conversion
  private Map<Long, IFCVO> linemap;

  // EXPRESS basis
  private final Map<String, EntityVO> ent;
  private final Map<String, TypeVO> typ;

  private StreamRDF streamRDF;
  private final File inputFile;
  private final OntModel ontModel;

  // for removing duplicates in line entries
  private Map<String, Resource> listOfUniqueResources;
  // Taking care of avoiding duplicate resources
  private ConcurrentHashMap<String, Resource> propertyResourceMap;

  // if true, the same entity is used for all identical property values found in the ifc file.
  // Upside: The resulting RDF model might have a lot fewer triples
  // Downside: Generating RDF is slower because of a lot of lookups
  // Downside: Generating RDF requires more RAM because of the lookup structures
  // Downside: The RDF has one entity for many actual values, so if that value is changed, it is changed for all values.
  private boolean avoidDuplicatePropertyResources = false;

  private boolean removeDuplicates = false;

  private static final Logger LOG = LoggerFactory.getLogger(RDFWriter.class);
  private TaskProgressListener progressListener;
  private TaskProgressReporter progressReporter;

  public RDFWriter(OntModel ontModel, File inputFile, String baseURI, Map<String, EntityVO> ent, Map<String, TypeVO> typ, String ontURI) {
    this.ontModel = ontModel;
    this.inputFile = inputFile;
    this.baseURI = baseURI;
    this.ent = ent;
    this.typ = typ;
    this.ontNS = ontURI + "#";
  }

  public void parseModelToOutputStream(OutputStream out) throws IOException {
	// CHANGED:  Jena  3.16.0    JO: 2020, added Context.emptyContext
    parseModelToStreamRdf(StreamRDFWriter.getWriterStream(out, RDFFormat.TURTLE_BLOCKS,Context.emptyContext));
  }

  public void parseModelToGraph(Graph graph) throws IOException {
    parseModelToStreamRdf(StreamRDFLib.graph(graph));
  }

  private static StreamRDF synchronizedStreamRDF(StreamRDF delegate) {
    BlockingQueue<Triple> tripleQueue = new ArrayBlockingQueue<>(100);
    AtomicBoolean running = new AtomicBoolean(true);
    Thread tripleHandler = new Thread(() -> {
      while( !tripleQueue.isEmpty() || running.get() ){
        try {
          delegate.triple(tripleQueue.take());
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    });
    tripleHandler.start();
    return new StreamRDF() {
      public void start() {
        synchronized(this) {
          delegate.start();
        }
      }

      public void triple(Triple triple) {
        synchronized(this) {
          delegate.triple(triple);
        }
      }

      public void quad(Quad quad) {
        synchronized(this) {
          delegate.quad(quad);
        }
      }

      public void base(String base) {
        synchronized(this) {
          delegate.base(base);
        }
      }

      public void prefix(String prefix, String iri) {
        synchronized(this) {
          delegate.prefix(prefix, iri);
        }
      }

      public void finish() {
        synchronized(this) {
          running.set(false);
          delegate.finish();
        }
      }
    };
  }

  public void parseModelToStreamRdf(StreamRDF writer) throws IOException {
    streamRDF = synchronizedStreamRDF(writer);
    parseModelToOutputStream();
  }

  private void parseModelToOutputStream() throws IOException {
    try {
      setup();
      IfcSpfParser parser = new IfcSpfParser(inputFile, removeDuplicates, progressListener);
      // Read the whole file into a linemap Map object
      parser.readModel();
      LOG.info("Model parsed");
      if (removeDuplicates) {
        parser.resolveDuplicates();
      }
      // map entries of the linemap Map object to the ontology Model and make
      // new instances in the model
      boolean parsedSuccessfully = parser.mapEntries();
      if (!parsedSuccessfully)
        return;
      //recover data from parser
      linemap = parser.getLinemap();
      LOG.info("Entries mapped, now creating instances");
      createInstances();
    }
    finally {
      close();
    }
  }

  private void setup() {
    if (avoidDuplicatePropertyResources){
      this.propertyResourceMap = new ConcurrentHashMap<>();
    }
    if (removeDuplicates) {
      this.listOfUniqueResources = new TreeMap<>();
    }
    streamRDF.base(baseURI);
    streamRDF.prefix("ifc", ontNS);
    streamRDF.prefix("inst", baseURI);
    streamRDF.prefix("list", LIST_NS);
    streamRDF.prefix("express", EXPRESS_NS);
    streamRDF.prefix("rdf", Namespace.RDF);
    streamRDF.prefix("xsd", Namespace.XSD);
    streamRDF.prefix("owl", Namespace.OWL);
    streamRDF.start();
    streamRDF.triple(new Triple(NodeFactory.createURI(baseURI), RDF.type.asNode(), OWL.Ontology.asNode()));
    streamRDF.triple(new Triple(NodeFactory.createURI(baseURI), OWL.imports.asNode(), NodeFactory.createURI(ontNS)));
  }

  private void close() {
    if (streamRDF != null) {
      streamRDF.finish();
    }
    if (this.propertyResourceMap != null) {
      this.propertyResourceMap.clear();
    }
    if (this.listOfUniqueResources != null){
      this.listOfUniqueResources.clear();
    }
    if (linemap != null){
      linemap.clear();
      linemap = null;
    }
  }

  public void setProgressListener(TaskProgressListener progressListener) {
    this.progressListener = progressListener;
  }

  private static class TypeRemembrance {
    private TypeVO typeVO;

    public TypeRemembrance() {
    }

    public TypeRemembrance(TypeVO typeVO) {
      this.typeVO = typeVO;
    }

    public TypeVO get() {
      return typeVO;
    }

    public void set(TypeVO typeVO) {
      this.typeVO = typeVO;
    }

    public boolean isEmpty(){
      return this.typeVO == null;
    }

    public boolean is(TypeVO other) {
      if (this.typeVO == null){
        return false;
      }
      return this.typeVO.equals(other);
    }

    public boolean isPresent() {
      return ! isEmpty();
    }

    public void clear() {
      this.typeVO = null;
    }
  }

  private final AtomicInteger cnt = new AtomicInteger(0);

  private void createInstances() throws IOException {
    LOG.info("ontology size : {}", ent.entrySet().size());
    LOG.info("linemap entries: {}", linemap.size());
      progressReporter = TaskProgressReporter.builder(progressListener, linemap.size())
                      .taskName("Generating Triples")
                      .messageGenerator(progressData -> String.format("generated triples for %.0f of %.0f entities",
                                      progressData.getPosition(),
                                      progressData.getTargetValue()))
                      .build();
    try {
      linemap.values()
             .stream()
             .parallel().forEach(this::generateTriplesForIfcVo);
    } catch (Exception e) {
      e.printStackTrace();
    }
    progressReporter.finished();
  }

  private void generateTriplesForIfcVo(IFCVO ifcLineEntry) {
    progressReporter.advanceBy(1);
    String typeName = "";
    if (ent.containsKey(ifcLineEntry.getName()))
      typeName = ent.get(ifcLineEntry.getName()).getName();
    else if (typ.containsKey(ifcLineEntry.getName()))
      typeName = typ.get(ifcLineEntry.getName()).getName();
    OntClass cl = ontModel.getOntClass(ontNS + typeName);
    Resource r = makeResourceWithSuffix(baseURI, typeName, ifcLineEntry.getLineNum());
    emitResource(r, cl);
    if (removeDuplicates) {
      listOfUniqueResources.put(ifcLineEntry.getFullLineAfterNum(), r);
    }
    if (LOG.isTraceEnabled()) {
      LOG.trace("-------------------------------");
      LOG.trace(r.getLocalName());
      LOG.trace("-------------------------------");
    }
    try {
      fillProperties(ifcLineEntry, r);
    } catch (Exception e){
      throw new RuntimeException("Error processing IFCVO " + ifcLineEntry.toString(), e);
    }
  }

  private void fillProperties(IFCVO ifcLineEntry, Resource r) throws IOException {
    String className = ExpressReader.formatClassName(ifcLineEntry.getName());
    EntityVO evo = ent.get(className);
    TypeVO tvo = typ.get(className);


    if (tvo == null && evo == null) {
      // This can actually never happen
      // Namely, if this is the case, then ERROR 2 should fire first,
      // after which the program stops
      LOG.error("ERROR 3*: fillProperties 1 - Type nor entity exists: {}", ifcLineEntry.getName() );
    }

    if (evo == null && tvo != null) {
    	//working with a TYPE

      TypeRemembrance typeRemembrance = new TypeRemembrance();
      for (Object o : ifcLineEntry.getObjectList()) {

        if (o instanceof String) {
          LOG.warn("*WARNING 1*: fillProperties 2: unhandled type property found.");
        } else if (o instanceof IFCVO) {
          LOG.warn("*WARNING 2*: fillProperties 2: unhandled type property found.");
        } else if (List.class.isAssignableFrom(o.getClass())) {
          if (LOG.isTraceEnabled()) {
            LOG.trace("fillProperties 3 - fillPropertiesHandleListObject(tvo)");
          }
          fillPropertiesHandleListObject(r, tvo, (List) o, typeRemembrance);
        }
      }
    }

    if (tvo == null && evo != null) {
    	//working with an ENTITY
      final String subject = evo.getName() + "_" + ifcLineEntry.getLineNum();

      TypeRemembrance typeRemembrance = new TypeRemembrance();
      int attributePointer = 0;
      for (Object o : ifcLineEntry.getObjectList()) {

        if (o instanceof String) {
          if (LOG.isTraceEnabled()) {
            LOG.trace("fillProperties 4 - fillPropertiesHandleStringObject(evo)");
          }
          attributePointer = fillPropertiesHandleStringObject(r, evo, subject, attributePointer, o, typeRemembrance);
        } else if (o instanceof IFCVO) {
          if (LOG.isTraceEnabled()) {
            LOG.trace("fillProperties 5 - fillPropertiesHandleIfcObject(evo)");
          }
          attributePointer = fillPropertiesHandleIfcObject(r, evo, attributePointer, o);
        } else if (List.class.isAssignableFrom(o.getClass())) {
          if (LOG.isTraceEnabled()) {
            LOG.trace("fillProperties 6 - fillPropertiesHandleListObject(evo)");
          }
          attributePointer = fillPropertiesHandleListObject(r, evo, attributePointer, (List) o, typeRemembrance);
        }
      }
    }
  }

  // --------------------------------------
  // 6 MAIN FILLPROPERTIES METHODS
  // --------------------------------------

  private int fillPropertiesHandleStringObject(Resource r, EntityVO evo, String subject, int attributePointer, Object o, TypeRemembrance typeRemembrance) throws IOException {
    if (! "$".equals(o) && ! "*".equals(o)) {
      TypeVO type = typ.get(ExpressReader.formatClassName((String) o));
      if (type == null) {
        if ((evo != null) && (evo.getDerivedAttributeList() != null)) {
          if (evo.getDerivedAttributeList().size() <= attributePointer) {
            LOG.error("*ERROR 4*: Entity in IFC files has more attributes than it is allowed have: " + subject);
            attributePointer++;
            return attributePointer;
          }

          final String propURI = ontNS + evo.getDerivedAttributeList().get(attributePointer).getLowerCaseName();
          final String literalString = filterExtras((String) o);

          OntProperty p = ontModel.getOntProperty(propURI);
          OntResource range = p.getRange();
          if (range.isClass()) {
            if (range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "ENUMERATION"))) {
              // Check for ENUM
              addEnumProperty(r, p, range, literalString);
            } else if (range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "SELECT"))) {
              // Check for SELECT
              if (LOG.isTraceEnabled()) {
                LOG.trace("*OK 25*: found subClass of SELECT Class, now doing nothing with it: {} - {} - {}", p,
                                range.getLocalName(), literalString);
              }
              createLiteralProperty(r, p, range, literalString);
            } else if (range.asClass().hasSuperClass(ontModel.getOntClass(LIST_NS + "OWLList"))) {
              // Check for LIST
              if (LOG.isTraceEnabled()) {
                LOG.trace("*WARNING 5*: found LIST property (but doing nothing with it): {} -- {} - {} - {}",
                                subject, p, range.getLocalName(), literalString);
              }
            } else {
              createLiteralProperty(r, p, range, literalString);
            }
          } else {
            LOG.warn("*WARNING 7*: found other kind of property: {} - {}", p ,range.getLocalName());
          }
        } else {
          LOG.warn("*WARNING 8*: Nothing happened. Not sure if this is good or bad, possible or not.");
        }
        attributePointer++;
      } else {
        typeRemembrance.set(type);
      }
    } else
      attributePointer++;
    return attributePointer;
  }

  private int fillPropertiesHandleIfcObject(Resource r, EntityVO evo, int attributePointer, Object o) throws IOException {
    if ((evo != null) && (evo.getDerivedAttributeList() != null) && (evo.getDerivedAttributeList().size() > attributePointer)) {

      final String propURI = ontNS + evo.getDerivedAttributeList().get(attributePointer).getLowerCaseName();
      EntityVO evorange = ent.get(ExpressReader.formatClassName(((IFCVO) o).getName()));

      OntProperty p = ontModel.getOntProperty(propURI);
      OntResource rclass = ontModel.getOntResource(ontNS + evorange.getName());

      Resource r1 = makeResourceWithSuffix(baseURI, evorange.getName(), ((IFCVO) o).getLineNum());
      streamRDF.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
      if (LOG.isTraceEnabled()) {
        LOG.trace("*OK 1*: added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName());
      }
    } else {
      LOG.warn("*WARNING 3*: Nothing happened. Not sure if this is good or bad, possible or not.");
    }
    attributePointer++;
    return attributePointer;
  }

  @SuppressWarnings("unchecked")
  private int fillPropertiesHandleListObject(Resource r, EntityVO evo, int attributePointer, List objectList, TypeRemembrance typeRemembrance) throws IOException {

    List<String> literals = new LinkedList<>();
    List<Resource> listRemembranceResources = new LinkedList<>();
    List<IFCVO> ifcVOs = new LinkedList<>();

    // process list
    for (int j = 0; j < objectList.size(); j++) {
      Object o1 = objectList.get(j);
      if (o1 instanceof String) {
        TypeVO t = typ.get(ExpressReader.formatClassName((String) o1));
        if (typeRemembrance.isEmpty()) {
          if (t != null) {
            typeRemembrance.set(t);
          } else {
            literals.add(filterExtras((String) o1));
          }
        } else {
          if (t != null) {
            if (typeRemembrance.is(t)) {
              // Ignore and continue with life
            } else {
              // Panic
              LOG.warn("*WARNING 37*: Found two different types in one list. This is worth checking.");
            }
          } else {
            literals.add(filterExtras((String) o1));
          }
        }
      } else if (o1 instanceof IFCVO) {
        if ((evo != null) && (evo.getDerivedAttributeList() != null) && (evo.getDerivedAttributeList().size() > attributePointer)) {

          String propURI = evo.getDerivedAttributeList().get(attributePointer).getLowerCaseName();
          OntProperty p = ontModel.getOntProperty(ontNS + propURI);
          OntResource typerange = p.getRange();

          if (typerange.asClass().hasSuperClass(ontModel.getOntClass(LIST_NS + "OWLList"))) {
            // EXPRESS LISTs
            String listvaluepropURI = ontNS + typerange.getLocalName().substring(0, typerange.getLocalName().length() - 5);
            OntResource listrange = ontModel.getOntResource(listvaluepropURI);

            if (listrange.asClass().hasSuperClass(ontModel.getOntClass(LIST_NS + "OWLList"))) {
              LOG.error("*ERROR 22*: Found supposedly unhandled ListOfList, but this should not be possible.");
            } else {
              fillClassInstanceList(objectList, typerange, p, r);
              j = objectList.size() - 1;
            }
          } else {
            // EXPRESS SETs
            EntityVO evorange = ent.get(ExpressReader.formatClassName(((IFCVO) o1).getName()));
            OntResource rclass = ontModel.getOntResource(ontNS + evorange.getName());

            Resource r1 = makeResourceWithSuffix(baseURI, evorange.getName(), ((IFCVO) o1).getLineNum());
            streamRDF.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
            if (LOG.isTraceEnabled()) {
              LOG.trace("*OK 5*: added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1
                              .getLocalName());
            }

          }
        } else {
          LOG.warn("*WARNING 13*: Nothing happened. Not sure if this is good or bad, possible or not.");
        }
      } else if (List.class.isAssignableFrom(o1.getClass())) {
        if (typeRemembrance.isPresent()) {
          List<Object> tmpListInList = (List<Object>) o1;
          for (int jj = 0; jj < tmpListInList.size(); jj++) {
            Object o2 = tmpListInList.get(jj);
            if (o2 instanceof String) {
              literals.add(filterExtras((String) o2));
            } else if (o2 instanceof IFCVO) {
              // Lists of IFC entities
              LOG.warn("*WARNING 30: Nothing happened. Not sure if this is good or bad, possible or not.");
            } else if (List.class.isAssignableFrom(o2.getClass())) {
              // this happens only for types that are equivalent
              // to lists (e.g. IfcLineIndex in IFC4_ADD1)
              // in this case, the elements of the list should be
              // treated as new instances that are equivalent to
              // the correct lists
              List<Object> tmpListInListInList = (List<Object>) o2;
              for (Object o3 : tmpListInListInList) {
                if (o3 instanceof String) {
                  literals.add(filterExtras((String) o3));
                } else {
                  LOG.warn("*WARNING 31: Nothing happened. Not sure if this is good or bad, possible or not.");
                }
              }

              // exception. when a list points to a number of
              // linked lists, it could be that there are multiple
              // different entities are referenced
              // example: #308=
              // IFCINDEXEDPOLYCURVE(#309,(IFCLINEINDEX((1,2)),IFCARCINDEX((2,3,4)),IFCLINEINDEX((4,5)),IFCARCINDEX((5,6,7))),.F.);
              // in this case, it is better to immediately print
              // all relevant entities and properties for each
              // case (e.g. IFCLINEINDEX((1,2))),
              // and reset typeremembrance for the next case (e.g.
              // IFCARCINDEX((4,5))).

              if ((evo != null) && (evo.getDerivedAttributeList() != null) && (evo.getDerivedAttributeList().size() > attributePointer)) {

                OntClass cl = ontModel.getOntClass(ontNS + typeRemembrance.get().getName());
                String uri = baseURI + typeRemembrance.get().getName();
                Resource r1 = makeResourceWithHashedSuffix(baseURI, typeRemembrance.get().getName(), r.getURI(), evo.getName(), j,jj);
                emitResource(r1, cl);
                OntResource range = ontModel.getOntResource(ontNS + typeRemembrance.get().getName());

                // finding listrange
                String[] primTypeArr = typeRemembrance.get().getPrimarytype().split(" ");
                String primType = ontNS + primTypeArr[primTypeArr.length - 1].replace(";", "");
                OntResource listrange = ontModel.getOntResource(primType);
                List<Object> literalObjects = new ArrayList<>(literals);
                addDirectRegularListProperty(r1, range, listrange, literalObjects, 0);

                // put relevant top list items in a list, which
                // can then be parsed at the end of this method
                listRemembranceResources.add(r1);
              }

              typeRemembrance.clear();
              literals.clear();
            } else {
              LOG.warn("*WARNING 35: Nothing happened. Not sure if this is good or bad, possible or not.");
            }
          }
        } else {
          List<Object> tmpListInList = (List<Object>) o1;
          for (Object o2 : tmpListInList) {
            if (o2 instanceof String) {
              literals.add(filterExtras((String) o2));
            } else if (o2 instanceof IFCVO) {
              ifcVOs.add((IFCVO) o2);
            } else if (List.class.isAssignableFrom(o2.getClass())) {
              LOG.error("*ERROR 19*: Found List of List of List. Code cannot handle that.");
            } else {
              LOG.warn("*WARNING 32*: Nothing happened. Not sure if this is good or bad, possible or not.");
            }
          }
          if ((evo != null) && (evo.getDerivedAttributeList() != null) && (evo.getDerivedAttributeList().size() > attributePointer)) {

            String propURI = ontNS + evo.getDerivedAttributeList().get(attributePointer).getLowerCaseName();
            OntProperty p = ontModel.getOntProperty(propURI);
            OntClass typerange = p.getRange().asClass();

            if (typerange.asClass().hasSuperClass(ontModel.getOntClass(LIST_NS + "OWLList"))) {
              String listvaluepropURI = typerange.getLocalName().substring(0, typerange.getLocalName().length() - 5);
              OntResource listrange = ontModel.getOntResource(ontNS + listvaluepropURI);
              final String uri = baseURI + listvaluepropURI;
              Resource r1 = makeResourceWithHashedSuffix(baseURI, listvaluepropURI, r.getURI(),evo.getName(), j);
              emitResource(r1, listrange);
              List<Object> objects = new ArrayList<>();
              if (!ifcVOs.isEmpty()) {
                objects.addAll(ifcVOs);
                OntResource listcontentrange = getListContentType(listrange.asClass());
                addDirectRegularListProperty(r1, listrange, listcontentrange, objects, 1);
              } else if (!literals.isEmpty()) {
                objects.addAll(literals);
                OntResource listcontentrange = getListContentType(listrange.asClass());
                addDirectRegularListProperty(r1, listrange, listcontentrange, objects, 0);
              }
              listRemembranceResources.add(r1);
            } else {
              LOG.error("*ERROR 23*: Impossible: found a list that is actually not a list.");
            }
          }

          literals.clear();
          ifcVOs.clear();
        }
      } else {
        LOG.error("*ERROR 11*: We found something that is not an IFC entity, not a list, not a string, and not a character. Check!");
      }
    }

    // interpret parse
    if (!literals.isEmpty()) {
      String propURI = ontNS + evo.getDerivedAttributeList().get(attributePointer).getLowerCaseName();
      OntProperty p = ontModel.getOntProperty(propURI);
      OntResource typerange = p.getRange();
      if (typeRemembrance.isPresent()) {
        if (evo.getDerivedAttributeList() != null && evo.getDerivedAttributeList().size() > attributePointer) {
          if (typerange.asClass().hasSuperClass(ontModel.getOntClass(LIST_NS + "OWLList"))) {
            addRegularListProperty(r, p, literals, typeRemembrance.get());
          } else {
            addSinglePropertyFromTypeRemembrance(r, p, literals.get(0), typeRemembrance.get());
            if (literals.size() > 1) {
              LOG.warn("*WARNING 37*: We are ignoring a number of literal values here.");
            }
          }
        } else {
          LOG.warn("*WARNING 15*: Nothing happened. Not sure if this is good or bad, possible or not.");
        }
        typeRemembrance.clear();
      } else if (evo.getDerivedAttributeList() != null && evo.getDerivedAttributeList().size() > attributePointer) {
        if (typerange.asClass().hasSuperClass(ontModel.getOntClass(LIST_NS + "OWLList")))
          addRegularListProperty(r, p, literals, null);
        else
          for (String literal : literals)
            createLiteralProperty(r, p, typerange, literal);
      }  else {
        LOG.warn("*WARNING 14*: Nothing happened. Not sure if this is good or bad, possible or not.");
      }
    }
    if (!listRemembranceResources.isEmpty()) {
      if ((evo != null) && (evo.getDerivedAttributeList() != null) && (evo.getDerivedAttributeList().size() > attributePointer)) {
        String propURI = ontNS + evo.getDerivedAttributeList().get(attributePointer).getLowerCaseName();
        OntProperty p = ontModel.getOntProperty(propURI);
        addListPropertyToGivenEntities(r, p, listRemembranceResources);
      }
    }
    attributePointer++;
    return attributePointer;
  }

  @SuppressWarnings({ "unchecked" })
  private void fillPropertiesHandleListObject(Resource r, TypeVO tvo, List<Object> objectList, TypeRemembrance typeRemembrance) throws IOException {

    List<String> literals = new LinkedList<>();

    // process list
    for (Object o1 : objectList) {
      if (o1 instanceof String) {
        TypeVO type = typ.get(ExpressReader.formatClassName((String) o1));
        if (type != null && typeRemembrance.isEmpty()) {
          typeRemembrance.set(type);
        } else
          literals.add(filterExtras((String) o1));
      } else if (o1 instanceof IFCVO) {
        if ((tvo != null)) {
          LOG.warn("*WARNING 16*: found TYPE that is equivalent to a list if IFC entities - below is the code used when this happens for ENTITIES with a list of ENTITIES");
        } else {
          LOG.warn("*WARNING 19*: Nothing happened. Not sure if this is good or bad, possible or not.");
        }
      } else if (List.class.isAssignableFrom(o1.getClass()) && typeRemembrance.isPresent()) {
        List<Object> tmpListInlist = (List<Object>) o1;
        for (Object o2 : tmpListInlist) {
          if (o2 instanceof String) {
            literals.add(filterExtras((String) o2));
          } else {
            LOG.warn("*WARNING 18*: Nothing happened. Not sure if this is good or bad, possible or not.");
          }
        }
      } else {
        LOG.error("*ERROR 10*: We found something that is not an IFC entity, not a list, not a string, and not a character. Check!");
      }
    }

    // interpret parse
    if (literals.isEmpty()) {
      if (typeRemembrance.isPresent()) {
        if ((tvo != null)) {
          LOG.warn("*WARNING 20*: this part of the code has not been checked - it can't be correct");

          String[] primtypeArr = tvo.getPrimarytype().split(" ");
          String primType = primtypeArr[primtypeArr.length - 1].replace(";", "") + "_" + primtypeArr[0].substring(0, 1).toUpperCase() + primtypeArr[0].substring(1).toLowerCase();
          String typeURI = ontNS + primType;
          OntResource range = ontModel.getOntResource(typeURI);
          OntResource listrange = getListContentType(range.asClass());
          List<Object> literalObjects = new ArrayList<>(literals);
          addDirectRegularListProperty(r, range, listrange, literalObjects, 0);
        } else {
          LOG.warn("*WARNING 21*: Nothing happened. Not sure if this is good or bad, possible or not.");
        }
        typeRemembrance.clear();
      } else if ((tvo != null)) {
        String[] primTypeArr = tvo.getPrimarytype().split(" ");
        String primType = primTypeArr[primTypeArr.length - 1].replace(";", "") + "_" + primTypeArr[0].substring(0, 1).toUpperCase() + primTypeArr[0].substring(1).toLowerCase();
        String typeURI = ontNS + primType;
        OntResource range = ontModel.getOntResource(typeURI);
        List<Object> literalObjects = new ArrayList<>(literals);
        OntResource listrange = getListContentType(range.asClass());
        addDirectRegularListProperty(r, range, listrange, literalObjects, 0);
      }
    }
  }

  // --------------------------------------
  // EVERYTHING TO DO WITH LISTS
  // --------------------------------------

  private void addSinglePropertyFromTypeRemembrance(Resource r, OntProperty p, String literalString, TypeVO typeremembrance) throws IOException {
    OntResource range = ontModel.getOntResource(ontNS + typeremembrance.getName());

    if (range.isClass()) {
      if (range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "ENUMERATION"))) {
        // Check for ENUM
        addEnumProperty(r, p, range, literalString);
      } else if (range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "SELECT"))) {
        // Check for SELECT
        if (LOG.isTraceEnabled()) {
          LOG.trace("*OK 24*: found subClass of SELECT Class, now doing nothing with it: " + p + " - " + range
                          .getLocalName() + " - " + literalString);
        }
        createLiteralProperty(r, p, range, literalString);
      } else if (range.asClass().hasSuperClass(ontModel.getOntClass(LIST_NS + "OWLList"))) {
        // Check for LIST
        LOG.warn("*WARNING 24*: found LIST property (but doing nothing with it): " + p + " - " + range.getLocalName() + " - " + literalString);
      } else {
        createLiteralProperty(r, p, range, literalString);
      }
    } else {
      LOG.warn("*WARNING 26*: found other kind of property: " + p + " - " + range.getLocalName());
    }
  }

  private void addEnumProperty(Resource r, Property p, OntResource range, String literalString) throws IOException {
    for (ExtendedIterator<? extends OntResource> instances = range.asClass().listInstances(); instances.hasNext();) {
      OntResource rangeInstance = instances.next();
      if (rangeInstance.getProperty(RDFS.label).getString().equalsIgnoreCase(filterPoints(literalString))) {
        streamRDF.triple(new Triple(r.asNode(), p.asNode(), rangeInstance.asNode()));
        if (LOG.isTraceEnabled()) {
          LOG.trace("*OK 2*: added ENUM statement " + r.getLocalName() + " - " + p.getLocalName() + " - "
                          + rangeInstance.getLocalName());
        }
        return;
      }
    }
    LOG.error("*ERROR 9*: did not find ENUM individual for " + literalString + "\r\nQuitting the application without output!");
  }

  private void addLiteralToResource(Resource r1, OntProperty valueProp, String xsdType, String literalString) throws IOException {
    if ("integer".equalsIgnoreCase(xsdType))
      addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDinteger));
    else if ("double".equalsIgnoreCase(xsdType))
      addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDdouble));
    else if ("hexBinary".equalsIgnoreCase(xsdType))
      addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDhexBinary));
    else if ("boolean".equalsIgnoreCase(xsdType)) {
      if (".F.".equalsIgnoreCase(literalString))
        addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral("false", XSDDatatype.XSDboolean));
      else if (".T.".equalsIgnoreCase(literalString))
        addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral("true", XSDDatatype.XSDboolean));
      else
        LOG.warn("*WARNING 10*: found odd boolean value: " + literalString);
    } else if ("logical".equalsIgnoreCase(xsdType)) {
      if (".F.".equalsIgnoreCase(literalString))
        addProperty(r1, valueProp, ontModel.getResource(EXPRESS_NS + "FALSE"));
      else if (".T.".equalsIgnoreCase(literalString))
        addProperty(r1, valueProp, ontModel.getResource(EXPRESS_NS + "TRUE"));
      else if (".U.".equalsIgnoreCase(literalString))
        addProperty(r1, valueProp, ontModel.getResource(EXPRESS_NS + "UNKNOWN"));
      else
        LOG.warn("*WARNING 9*: found odd logical value: " + literalString);
    } else if ("string".equalsIgnoreCase(xsdType))
      addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral(literalString, XSDDatatype.XSDstring));
    else
      addLiteral(r1, valueProp, ResourceFactory.createTypedLiteral(literalString));
    if (LOG.isTraceEnabled()) {
      LOG.trace("*OK 4*: added literal: " + r1.getLocalName() + " - " + valueProp + " - " + literalString);
    }
  }

  // LIST HANDLING
  private void addDirectRegularListProperty(Resource r, OntResource range, OntResource listrange, List<Object> el, int mySwitch) throws IOException {
    if (range.isClass()) {
      if (listrange.asClass().hasSuperClass(ontModel.getOntClass(LIST_NS + "OWLList"))) {
        LOG.warn("*WARNING 27*: Found unhandled ListOfList");
      } else {
        List<Resource> reslist = new ArrayList<>();
        // createrequirednumberofresources
        for (int i = 0; i < el.size(); i++) {
          if (i == 0)
            reslist.add(r);
          else {
            final String uri = baseURI + range.getLocalName();
            Resource r1 = makeResourceWithHashedSuffix(baseURI, range.getLocalName(), r.getURI(), i);
            emitResource(r1, range);
            reslist.add(r1);
          }
        }

        if (mySwitch == 0) {
          // bind the properties with literal values only if we are
          // actually dealing with literals
          List<String> literals = new ArrayList<>();
          for (Object o : el) {
            literals.add((String) o);
          }
          addListInstanceProperties(reslist, literals, listrange);
        } else {
          for (int i = 0; i < reslist.size(); i++) {
            Resource r1 = reslist.get(i);
            IFCVO vo = (IFCVO) el.get(i);
            EntityVO evorange = ent.get(ExpressReader.formatClassName((vo).getName()));
            OntResource rclass = ontModel.getOntResource(ontNS + evorange.getName());
            Resource r2 = makeResourceWithSuffix(baseURI, evorange.getName(), vo.getLineNum());
            emitResource(r2, rclass);
            if (LOG.isTraceEnabled()) {
              LOG.trace("*OK 21*: created resource: " + r2.getLocalName());
            }
            streamRDF.triple(new Triple(r1.asNode(), ontModel.getOntProperty(LIST_NS + "hasContents").asNode(), r2.asNode()));
            if (LOG.isTraceEnabled()) {
              LOG.trace("*OK 22*: added property: " + r1.getLocalName() + " - " + "-hasContents-" + " - " + r2
                              .getLocalName());
            }

            if (i < el.size() - 1) {
              streamRDF.triple(new Triple(r1.asNode(), ontModel.getOntProperty(LIST_NS + "hasNext").asNode(), reslist.get(i + 1).asNode()));
              if (LOG.isTraceEnabled()) {
                LOG.trace("*OK 23*: added property: " + r1.getLocalName() + " - " + "-hasNext-" + " - " + reslist
                                .get(i + 1).getLocalName());
              }
            }
          }
        }
      }
    }
  }

  private void addRegularListProperty(Resource r, OntProperty p, List<String> el, TypeVO typeRemembranceOverride) throws IOException {
    OntResource range = p.getRange();
    if (range.isClass()) {
      OntResource listrange = getListContentType(range.asClass());
      if (typeRemembranceOverride != null) {
        listrange = ontModel.getOntClass(ontNS + typeRemembranceOverride.getName());
      }

      if (listrange == null) {
        LOG.error("*ERROR 14*: We could not find what kind of content is expected in the LIST.");
      } else {
        if (listrange.asClass().hasSuperClass(ontModel.getOntClass(LIST_NS + "OWLList"))) {
          LOG.warn("*WARNING 28*: Found unhandled ListOfList");
        } else {
          List<Resource> reslist = new ArrayList<>();
          // createrequirednumberofresources
          for (int ii = 0; ii < el.size(); ii++) {
            Resource r1 = makeResourceWithHashedSuffix(baseURI, range.getLocalName(), r.getURI(), p.getURI(), listrange.getURI(), el.get(ii), ii);
            emitResource(r1, range);
            reslist.add(r1);
            if (ii == 0) {
              streamRDF.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
              if (LOG.isTraceEnabled()) {
                LOG.trace("*OK 7*: added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1
                                .getLocalName());
              }
            }
          }
          // bindtheproperties
          addListInstanceProperties(reslist, el, listrange);
        }
      }
    }
  }

  private void createLiteralProperty(Resource r, OntResource p, OntResource range, String literalString) throws IOException {
    String xsdType = getXSDTypeFromRange(range);
    if (xsdType == null) {
      xsdType = getXSDTypeFromRangeExpensiveMethod(range);
    }
    if (xsdType != null) {
      String xsdTypeCAP = Character.toUpperCase(xsdType.charAt(0)) + xsdType.substring(1);
      OntProperty valueProp = ontModel.getOntProperty(EXPRESS_NS + "has" + xsdTypeCAP);
      String key = valueProp.toString() + ":" + xsdType + ":" + literalString;
      Resource r1;
      if (avoidDuplicatePropertyResources){
        r1 = propertyResourceMap.computeIfAbsent(key, s -> {
          Resource res = makeResourceWithHashedSuffix(baseURI, range.getLocalName(), key);
          streamRDF.triple(new Triple(res.asNode(), RDF.type.asNode(), range.asNode()));
          if (LOG.isTraceEnabled()) {
            LOG.trace("*OK 17*: created resource: " + res.getLocalName());
          }
          return res;
        });
      } else {
        r1 = makeResourceWithHashedSuffix(baseURI, range.getLocalName(), r.getURI(), p.getURI(), key);
        streamRDF.triple(new Triple(r1.asNode(), RDF.type.asNode(), range.asNode()));
        if (LOG.isTraceEnabled()) {
          LOG.trace("*OK 17*: created resource: " + r1.getLocalName());
        }
      }
      try {
        addLiteralToResource(r1, valueProp, xsdType, literalString);
      } catch (IOException e) {
        throw new RuntimeException("Error adding property value", e);
      }
      streamRDF.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
      if (LOG.isTraceEnabled()) {
        LOG.trace("*OK 3*: added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1.getLocalName());
      }
    } else {
      LOG.error("*ERROR 1*: XSD type not found for: " + p + " - " + range.getURI() + " - " + literalString);
    }
  }

  private void addListPropertyToGivenEntities(Resource r, OntProperty p, List<Resource> el) throws IOException {
    OntResource range = p.getRange();
    if (range.isClass()) {
      OntResource listrange = getListContentType(range.asClass());

      if (listrange != null) {
        if (listrange.asClass().hasSuperClass(ontModel.getOntClass(LIST_NS + "OWLList"))) {
          if (LOG.isTraceEnabled()) {
            LOG.trace("*OK 20*: Handling list of list");
          }
        }
        for (int i = 0; i < el.size(); i++) {
          Resource r1 = el.get(i);
          final String uri = baseURI + range.getLocalName();
          Resource r2 = ResourceFactory.createResource(uri + "_" + makeIdSuffix(r.getURI(), p.getURI(), r1.getURI(), i, 1, uri)); // was
          // listrange
          streamRDF.triple(new Triple(r2.asNode(), RDF.type.asNode(), range.asNode()));
          if (LOG.isTraceEnabled()) {
            LOG.trace("*OK 14*: added property: " + r2.getLocalName() + " - rdf:type - " + range.getLocalName());
          }
          Resource r3 = ResourceFactory.createResource(uri + "_" + makeIdSuffix(r.getURI(), p.getURI(), r1.getURI(), i, 2, uri));

          if (i == 0) {
            streamRDF.triple(new Triple(r.asNode(), p.asNode(), r2.asNode()));
            if (LOG.isTraceEnabled()) {
              LOG.trace("*OK 15*: added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r2
                              .getLocalName());
            }
          }
          streamRDF.triple(new Triple(r2.asNode(), ontModel.getOntProperty(LIST_NS + "hasContents").asNode(), r1.asNode()));
          if (LOG.isTraceEnabled()) {
            LOG.trace("*OK 16*: added property: " + r2.getLocalName() + " - " + "-hasContents-" + " - " + r1
                            .getLocalName());
          }
          if (i < el.size() - 1) {
            streamRDF.triple(new Triple(r2.asNode(), ontModel.getOntProperty(LIST_NS + "hasNext").asNode(), r3.asNode()));
            if (LOG.isTraceEnabled()) {
              LOG.trace("*OK 17*: added property: " + r2.getLocalName() + " - " + "-hasNext-" + " - " + r3
                              .getLocalName());
            }
          }
        }
      }
    }
  }

  private void fillClassInstanceList(List<Object> tmpList, OntResource typerange, OntProperty p, Resource r) throws IOException {
    List<Resource> reslist = new ArrayList<>();
    List<IFCVO> entlist = new ArrayList<>();

    // createrequirednumberofresources
    for (int i = 0; i < tmpList.size(); i++) {
      Object o =  tmpList.get(i);
      if (o instanceof IFCVO) {
        IFCVO ifcvo = (IFCVO) o;
        Resource r1 = makeResourceWithHashedSuffix(baseURI, typerange.getLocalName(), r.getURI(), p.getURI(), i);
        emitResource(r1, typerange);
        reslist.add(r1);
        entlist.add(ifcvo);
        if (i == 0) {
          streamRDF.triple(new Triple(r.asNode(), p.asNode(), r1.asNode()));
          if (LOG.isTraceEnabled()) {
            LOG.trace("*OK 13*: added property: " + r.getLocalName() + " - " + p.getLocalName() + " - " + r1
                            .getLocalName());
          }
        }
      }
    }
    addClassInstanceListProperties(reslist, entlist);
  }

  private void addClassInstanceListProperties(List<Resource> reslist, List<IFCVO> entlist) throws IOException {
    OntProperty listp = ontModel.getOntProperty(LIST_NS + "hasContents");
    OntProperty isfollowed = ontModel.getOntProperty(LIST_NS + "hasNext");

    for (int i = 0; i < reslist.size(); i++) {
      Resource r = reslist.get(i);

      OntResource rclass;
      IFCVO ifcvo = entlist.get(i);
      String className = ExpressReader.formatClassName(ifcvo.getName());
      EntityVO evorange = ent.get(className);
      if (evorange == null) {
        TypeVO typerange = typ.get(className);
        rclass = ontModel.getOntResource(ontNS + typerange.getName());
        Resource r1 = makeResourceWithSuffix(baseURI, typerange.getName(), ifcvo.getLineNum());
        emitResource(r1, rclass);
        streamRDF.triple(new Triple(r.asNode(), listp.asNode(), r1.asNode()));
        if (LOG.isTraceEnabled()) {
          LOG.trace("*OK 8*: created property: " + r.getLocalName() + " - " + listp.getLocalName() + " - " + r1
                          .getLocalName());
        }
      } else {
        Resource r1 = makeResourceWithSuffix(baseURI, evorange.getName(), ifcvo.getLineNum());
        streamRDF.triple(new Triple(r.asNode(), listp.asNode(), r1.asNode()));
        if (LOG.isTraceEnabled()) {
          LOG.trace("*OK 9*: created property: " + r.getLocalName() + " - " + listp.getLocalName() + " - " + r1
                          .getLocalName());
        }
      }

      if (i < reslist.size() - 1) {
        streamRDF.triple(new Triple(r.asNode(), isfollowed.asNode(), reslist.get(i + 1).asNode()));
        if (LOG.isTraceEnabled()) {
          LOG.trace("*OK 10*: created property: " + r.getLocalName() + " - " + isfollowed.getLocalName() + " - "
                          + reslist.get(i + 1).getLocalName());
        }
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
      OntProperty valueProp = ontModel.getOntProperty(EXPRESS_NS + "has" + xsdTypeCAP);
      // Adding Content only if found
      for (int i = 0; i < reslist.size(); i++) {
        Resource r = reslist.get(i);
        String literalString = listelements.get(i);
        String key = valueProp.toString() + ":" + xsdType + ":" + literalString;
        final String uri = baseURI + listrange.getLocalName();
        Resource r2;
        if (avoidDuplicatePropertyResources){
          r2 = propertyResourceMap.computeIfAbsent(key, s -> {
            Resource res = makeResourceWithHashedSuffix(baseURI, listrange.getLocalName(), key);
            streamRDF.triple(new Triple(res.asNode(), RDF.type.asNode(), listrange.asNode()));
            if (LOG.isTraceEnabled()) {
              LOG.trace("*OK 19*: created resource: " + res.getLocalName());
            }
            return res;
          });
        } else {
          r2 = makeResourceWithHashedSuffix(baseURI, listrange.getLocalName(), r.getURI(), key);
          streamRDF.triple(new Triple(r2.asNode(), RDF.type.asNode(), listrange.asNode()));
          if (LOG.isTraceEnabled()) {
            LOG.trace("*OK 19*: created resource: " + r2.getLocalName());
          }
        }
        try {
          addLiteralToResource(r2, valueProp, xsdType, literalString);
        } catch (IOException e) {
          throw new RuntimeException("Error adding property value", e);
        }
        streamRDF.triple(new Triple(r.asNode(), ontModel.getOntProperty(LIST_NS + "hasContents").asNode(),
                        r2.asNode()));

        if (LOG.isTraceEnabled()) {
          LOG.trace("*OK 11*: added property: " + r.getLocalName() + " - " + "-hasContents-" + " - " + r2
                          .getLocalName());
        }

        if (i < listelements.size() - 1) {
          streamRDF.triple(new Triple(r.asNode(), ontModel.getOntProperty(LIST_NS + "hasNext").asNode(), reslist.get(i + 1).asNode()));
          if (LOG.isTraceEnabled()) {
            LOG.trace("*OK 12*: added property: " + r.getLocalName() + " - " + "-hasNext-" + " - " + reslist.get(i + 1)
                            .getLocalName());
          }
        }
      }
    } else {
      LOG.error("*ERROR 5*: XSD type not found for: " + listrange.getLocalName());
    }
  }

  // HELPER METHODS
  private String filterExtras(String txt) {
    StringBuilder sb = new StringBuilder();
    for (int n = 0; n < txt.length(); n++) {
      char ch = txt.charAt(n);
      switch (ch) {
        case '\'':
        case '=':
          break;
        default:
        sb.append(ch);
      }
    }
    return sb.toString();
  }

  private String filterPoints(String txt) {
    StringBuilder sb = new StringBuilder();
    for (int n = 0; n < txt.length(); n++) {
      char ch = txt.charAt(n);
      if (ch == '.') {
      } else {
        sb.append(ch);
      }
    }
    return sb.toString();
  }

  private void addLiteral(Resource r, OntProperty valueProp, Literal l) {
    streamRDF.triple(new Triple(r.asNode(), valueProp.asNode(), l.asNode()));
  }

  private void addProperty(Resource r, OntProperty valueProp, Resource r1) {
    streamRDF.triple(new Triple(r.asNode(), valueProp.asNode(), r1.asNode()));
  }

  private OntResource getListContentType(OntClass range) throws IOException {
    String resourceURI = range.asClass().getURI();
    if ((EXPRESS_NS + "STRING_List").equalsIgnoreCase(resourceURI) 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "STRING_List")))
      return ontModel.getOntResource(EXPRESS_NS + "STRING");
    else if ((EXPRESS_NS + "REAL_List").equalsIgnoreCase(resourceURI) 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "REAL_List")))
      return ontModel.getOntResource(EXPRESS_NS + "REAL");
    else if ((EXPRESS_NS + "INTEGER_List").equalsIgnoreCase(resourceURI) 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "INTEGER_List")))
      return ontModel.getOntResource(EXPRESS_NS + "INTEGER");
    else if ((EXPRESS_NS + "BINARY_List").equalsIgnoreCase(resourceURI) 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "BINARY_List")))
      return ontModel.getOntResource(EXPRESS_NS + "BINARY");
    else if ((EXPRESS_NS + "BOOLEAN_List").equalsIgnoreCase(resourceURI) 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "BOOLEAN_List")))
      return ontModel.getOntResource(EXPRESS_NS + "BOOLEAN");
    else if ((EXPRESS_NS + "LOGICAL_List").equalsIgnoreCase(resourceURI) 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "LOGICAL_List")))
      return ontModel.getOntResource(EXPRESS_NS + "LOGICAL");
    else if ((EXPRESS_NS + "NUMBER_List").equalsIgnoreCase(resourceURI) 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "NUMBER_List")))
      return ontModel.getOntResource(EXPRESS_NS + "NUMBER");
    else if (range.asClass().hasSuperClass(ontModel.getOntClass(LIST_NS + "OWLList"))) {
      String listvaluepropURI = ontNS + range.getLocalName().substring(0, range.getLocalName().length() - 5);
      return ontModel.getOntResource(listvaluepropURI);
    } else {
      LOG.warn("*WARNING 29*: did not find listcontenttype for : {}",  range.getLocalName());
      return null;
    }
  }

  private String getXSDTypeFromRange(OntResource range) {
    if (range.asClass().getURI().equalsIgnoreCase(EXPRESS_NS + "STRING") 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "STRING")))
      return "string";
    else if (range.asClass().getURI().equalsIgnoreCase(EXPRESS_NS + "REAL") 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "REAL")))
      return "double";
    else if (range.asClass().getURI().equalsIgnoreCase(EXPRESS_NS + "INTEGER") 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "INTEGER")))
      return "integer";
    else if (range.asClass().getURI().equalsIgnoreCase(EXPRESS_NS + "BINARY") 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "BINARY")))
      return "hexBinary";
    else if (range.asClass().getURI().equalsIgnoreCase(EXPRESS_NS + "BOOLEAN") 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "BOOLEAN")))
      return "boolean";
    else if (range.asClass().getURI().equalsIgnoreCase(EXPRESS_NS + "LOGICAL") 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "LOGICAL")))
      return "logical";
    else if (range.asClass().getURI().equalsIgnoreCase(EXPRESS_NS + "NUMBER") 
            || range.asClass().hasSuperClass(ontModel.getOntClass(EXPRESS_NS + "NUMBER")))
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

  private void emitResource(Resource resource, OntResource rclass) {
    streamRDF
                    .triple(
                                    new Triple(
                                                    resource.asNode(),
                                                    RDF.type.asNode(),
                                                    rclass.asNode()));
  }

  public boolean isRemoveDuplicates() {
    return removeDuplicates;
  }

  public void setRemoveDuplicates(boolean removeDuplicates) {
    this.removeDuplicates = removeDuplicates;
  }

  public boolean isAvoidDuplicatePropertyResources() {
    return avoidDuplicatePropertyResources;
  }

  public void setAvoidDuplicatePropertyResources(boolean avoidDuplicatePropertyResources) {
    this.avoidDuplicatePropertyResources = avoidDuplicatePropertyResources;
  }

  private final ThreadLocal<MessageDigest> messageDigestThreadLocal = new ThreadLocal<>();

  private Resource makeResourceWithSuffix(String prefix, String localName, Object suffix){
    return ResourceFactory.createResource(prefix + localName + "_" + suffix.toString());
  }

  private Resource makeResourceWithHashedSuffix(String prefix, String localname, Object... suffixComponents){
    String suffix = makeIdSuffix(prefix, localname, suffixComponents);
    return makeResourceWithSuffix(prefix, localname, suffix);
  }

  private String makeIdSuffix(String prefix, String localname, Object... suffixComponents){
    String key = makeKey(prefix, localname, suffixComponents);
    return hash(key);
  }

  private String makeIdSuffix(Object... forKeyComponents){
    String key = makeKey(forKeyComponents);
    return hash(key);
  }

  private String hash(String key) {
    MessageDigest digest = getMessageDigest();
    byte[] encodedhash = digest.digest(
                    key.getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(encodedhash);
  }

  private String makeKey(Object[] forKeyComponents) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < forKeyComponents.length; i++) {
      if (forKeyComponents[i] == null) {
        sb.append("[null]-")
                        .append(i)
                        .append("_");
      } else {
        sb.append(forKeyComponents[i].toString())
                        .append("-")
                        .append(i)
                        .append("_");
      }
    }
    return sb.toString();
  }

  private String makeKey(String first, String second, Object[] forKeyComponents) {
    StringBuilder sb = new StringBuilder();
    appendKeyComponent(sb, first, 0);
    appendKeyComponent(sb, second, 1);
    for (int i = 0; i < forKeyComponents.length; i++) {
      Object o = forKeyComponents[i];
      appendKeyComponent(sb, o, i+2);
    }
    return sb.toString();
  }

  private void appendKeyComponent(StringBuilder sb, Object o, int i) {
    if (o == null) {
      sb.append("[null]").append(i);
    } else {
      sb.append(o.toString()).append(i);
    }
    sb.append("_");
  }

  private MessageDigest getMessageDigest() {
    MessageDigest md = messageDigestThreadLocal.get();
    if (md == null) {
      try {
        md = MessageDigest.getInstance("SHA-256");
        messageDigestThreadLocal.set(md);
      } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException("Cannot obtain SHA-256 digest", e);
      }
    }
    return md;
  }

  private static String bytesToHex(byte[] hash) {
    StringBuilder hexString = new StringBuilder(2 * hash.length);
    for (byte b : hash) {
      String hex = Integer.toHexString(0xff & b);
      if (hex.length() == 1) {
        hexString.append('0');
      }
      hexString.append(hex);
    }
    return hexString.toString();
  }

}
