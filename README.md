# IFCtoRDF
IFCtoRDF is a set of reusable Java components that allows to parse IFC-SPF files and convert them into [RDF](https://www.w3.org/standards/techs/rdf#w3c_all) graphs. 

## Usage
This code does not have a Graphical User Interface (GUI). It relies on maven, so the code needs to be compiled using maven first. It can be run using the command line interface:

To convert an input file (IFC-SPFF) to an output file (TTL):
``
java -jar IfcSpfReader.jar [--keep-duplicates] <input_file> <output_file>
``

or to convert all the IFC-SPFF files in an entire directory:
``
java -jar IfcSpfReader.jar [--keep-duplicates] --dir <directory>"
``

## ifcOWL compatibility
The output RDF graphs follow the ifcOWL ontology, of which a number of versions are available:

- https://standards.buildingsmart.org/IFC/DEV/IFC2x3/FINAL/OWL/
- https://standards.buildingsmart.org/IFC/DEV/IFC2x3/TC1/OWL/
- https://standards.buildingsmart.org/IFC/DEV/IFC4/ADD1/OWL/
- https://standards.buildingsmart.org/IFC/DEV/IFC4/ADD2/OWL/
- https://standards.buildingsmart.org/IFC/DEV/IFC4/ADD2_TC1/OWL/
- https://standards.buildingsmart.org/IFC/DEV/IFC4/FINAL/OWL/
- https://standards.buildingsmart.org/IFC/DEV/IFC4_1/OWL/

Each of these ontologies rely on the EXPRESS and LIST ontologies:
- https://w3id.org/express/
- https://w3id.org/list/

## Javadoc
https://pipauwel.github.io/IFCtoRDF/0.2/apidocs/

## Dependencies
This code relies on two other libraries, namely an [EXPRESStoOWL library](https://github.com/pipauwel/EXPRESStoOWL) and [IFCParserLib library](https://github.com/pipauwel/ifcParserLib).

## Test files
This repository also contains the test files used for this code, including the expected logs. These files are in the [testfiles folder](https://github.com/pipauwel/IFCtoRDF/tree/master/testfiles). These are small samples, but they cover most of the code and most of the most common cases in IFC-SPFF files used in the construction industry.

## Bugs and contact
Please report bugs and errors (pipauwel.pauwels@ugent.be) - your input in making this code better is heavily appreciated.