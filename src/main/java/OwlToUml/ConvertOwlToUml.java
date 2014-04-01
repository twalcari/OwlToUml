package OwlToUml;

import com.clarkparsia.pellet.owlapiv3.PelletReasonerFactory;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.util.OWLOntologyMerger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;

/**
 * Hello world!
 */
public class ConvertOwlToUml {

    public static final String UNDEFINED = "Undefined";
    private final OWLReasonerFactory reasonerFactory;
    private final Set<OWLOntology> ontologies;
    private final OWLOntology ontology;
    private final PrintStream out;
    private final Multimap<String, OWLDataProperty> propertiesMap = HashMultimap.create();
    private int unknownCounter = 0;

    private ConvertOwlToUml(OWLReasonerFactory reasonerFactory,
                            Set<OWLOntology> ontologies, OWLOntology ontology, PrintStream out) {
        this.reasonerFactory = reasonerFactory;
        this.ontologies = ontologies;
        this.ontology = ontology;
        this.out = out;
    }

    public static void main(String[] args) throws OWLException, IOException, InterruptedException {
        if (args.length == 0) {
            System.out.println("Usage: OUTPUTFILE INPUTFILES");
            System.exit(0);
        }

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

        //setup output
        PrintStream out = System.out;

        if (!args[0].toLowerCase().equals("sysout")) {
            File file = new File(args[0]);

            if (file.exists())
                throw new RuntimeException("Output file already exists");

            out = new PrintStream(new FileOutputStream(file));
        }

        // load all ontologies

        for (int i = 1; i < args.length; i++) {
            IRI documentIRI = IRI.create(new File(args[i]));

            // Now load the ontology.
            System.out.println("Loading: " + documentIRI);
            manager.loadOntologyFromOntologyDocument(documentIRI);
        }
        OWLOntologyMerger merger = new OWLOntologyMerger(manager);
        IRI mergedOntologyIRI = IRI
                .create("http://example.org");
        OWLOntology ontology = merger
                .createMergedOntology(manager, mergedOntologyIRI);

        out.println("@startuml");
        out.println("hide methods");

        ConvertOwlToUml convertOwlToUml = new ConvertOwlToUml(
                new PelletReasonerFactory(),   //alternatively:  new org.semanticweb.HermiT.Reasoner.ReasonerFactory(),
                manager.getOntologies(), ontology, out);

        System.err.println("Precomputing data properties");
        OWLDataProperty dataProperty = manager.getOWLDataFactory().getOWLTopDataProperty();
        convertOwlToUml.preprocessDataProperties(dataProperty);

        System.err.println("Printing hierarchy");
        // Get Thing
        OWLClass clazz = manager.getOWLDataFactory().getOWLThing();
        // Print the hierarchy below Thing
        convertOwlToUml.printHierarchy(clazz);

        out.flush();
        System.err.println("Printing object properties");

        OWLObjectProperty property = manager.getOWLDataFactory().getOWLTopObjectProperty();
        convertOwlToUml.printObjectProperties(property);

        out.println("@enduml");
        System.err.println("Done");

        out.flush();
        out.close();
    }

    /*
     * Class Hierarchy
     */

    /**
     * Print the class hierarchy for the given ontology from this class down,
     * assuming this class is at the given level. Makes no attempt to deal
     * sensibly with multiple inheritance.
     */
    private void printHierarchy(OWLClass clazz) throws OWLException {
        OWLReasoner reasoner = reasonerFactory.createNonBufferingReasoner(ontology);

        //we skip the Upper level to make the output more readable.
        //printHierarchy(reasoner, null, clazz, 0);
        for (OWLClass child : reasoner.getSubClasses(clazz, true).getFlattened()) {
            printHierarchy(reasoner, null, child);
        }

        /* Now print out any unsatisfiable classes */
        for (OWLClass cl : ontology.getClassesInSignature()) {
            if (!reasoner.isSatisfiable(cl)) {
                out.println("XXX: " + labelFor(cl));
            }
        }

        if (propertiesMap.containsKey(UNDEFINED)) {
            out.println("class " + UNDEFINED + " {");
            for (OWLDataProperty dataProperty : propertiesMap.get(UNDEFINED)) {
                out.println("\t" + dataProperty.getIRI().getFragment());
            }

            out.println("}");
        }

        reasoner.dispose();
    }

    private String labelFor(OWLNamedObject namedObject) {
        return namedObject.getIRI().getFragment().replace("-", "");
    }

    private String labelForClass(OWLNamedObject namedObject) {
        return labelFor(namedObject) + "<" + namedObject.getIRI().getNamespace() + ">";
    }

    /**
     * Print the class hierarchy from this class down, assuming this class is at
     * the given level. Makes no attempt to deal sensibly with multiple
     * inheritance.
     */
    private void printHierarchy(OWLReasoner reasoner, OWLClass parentClazz, OWLClass clazz)
            throws OWLException {
        /*
         * Only print satisfiable classes -- otherwise we end up with bottom
         * everywhere
         */
        if (reasoner.isSatisfiable(clazz)) {

            out.println("class " + labelForClass(clazz) + "{");
            for (OWLDataProperty dataProperty : propertiesMap.get(clazz.getIRI().toString())) {
                out.println("\t" + labelFor(dataProperty));
            }
            out.println("}");

            if (parentClazz != null) {
                out.println(labelFor(parentClazz) + " <|-- " + labelFor(clazz));
            }

            /* Find the children and recurse */
            for (OWLClass child : reasoner.getSubClasses(clazz, true).getFlattened()) {
                if (!child.equals(clazz)) {
                    printHierarchy(reasoner, clazz, child);
                }
            }
        }
    }

    /*
     * Data properties
     */

    /**
     * This method fills fields-list of each class by analysing the domains of each DataProperty in the Ontology.
     * If no domain is specified, the DataProperty is added to the class UNDEFINED
     *
     * @param property
     */
    public void preprocessDataProperties(OWLDataProperty property) {
        OWLReasoner reasoner = reasonerFactory.createNonBufferingReasoner(ontology);
        reasoner.precomputeInferences(InferenceType.DATA_PROPERTY_HIERARCHY);

        //skip topDataProperty
        //preprocessDataProperty(reasoner, property);
        for (Node<OWLDataProperty> childNode : reasoner.getSubDataProperties(property, false)) {
            preprocessDataProperty(reasoner, childNode.getRepresentativeElement());
        }

    }

    /**
     * This method fills fields-list of each class by analysing the domains of each DataProperty in the Ontology.
     * If no domain is specified, the DataProperty is added to the class UNDEFINED
     *
     * @param reasoner
     * @param property
     */
    private void preprocessDataProperty(OWLReasoner reasoner, OWLDataProperty property) {

        if (property.isBottomEntity())
            return;

        Set<OWLClassExpression> domainClasses = property.getDomains(ontologies);

        for (OWLClassExpression domainClazz : domainClasses) {
            for (OWLClassExpression domainClazz2 : domainClazz.asDisjunctSet()) {
                String className = domainClazz2.isAnonymous() ? UNDEFINED : domainClazz2.asOWLClass().getIRI().toString();
                propertiesMap.put(className, property);
            }
        }

        if (domainClasses.isEmpty()) {
            propertiesMap.put(UNDEFINED, property);
        }


        for (Node<OWLDataProperty> childNode : reasoner.getSubDataProperties(property, false)) {
            preprocessDataProperty(reasoner, childNode.getRepresentativeElement());
        }
    }

    /*
     * Object Properties
     */

    public void printObjectProperties(OWLObjectProperty property) {
        OWLReasoner reasoner = reasonerFactory.createNonBufferingReasoner(ontology);
        reasoner.precomputeInferences(InferenceType.OBJECT_PROPERTY_HIERARCHY);

        //skip topObjectProperty
        //printObjectProperty(reasoner, property);
        for (Node<OWLObjectPropertyExpression> childNode : reasoner.getSubObjectProperties(property, true)) {
            printObjectProperty(reasoner, childNode.getRepresentativeElement());
        }

    }

    /**
     * This recursive methods prints an ObjectProperty as a relationship between two UML classes
     *
     * @param reasoner
     * @param property
     */
    private void printObjectProperty(OWLReasoner reasoner, OWLObjectPropertyExpression property) {
        if (property.isBottomEntity())
            return;


        Set<OWLClassExpression> domainClasses = property.getDomains(ontologies);
        Set<OWLClassExpression> rangeClasses = property.getRanges(ontologies);


        if (domainClasses.isEmpty()) {
            String domainClazzName = UNDEFINED;
            printObjectPropertyRanges(property, domainClazzName, rangeClasses);
        }
        for (OWLClassExpression domainClazz : domainClasses) {
            for (OWLClassExpression domainClazz2 : domainClazz.asDisjunctSet()) {
                String domainClazzName = domainClazz2.isAnonymous() ? UNDEFINED : labelFor(domainClazz2.asOWLClass());
                printObjectPropertyRanges(property, domainClazzName, rangeClasses);
            }
        }


        for (Node<OWLObjectPropertyExpression> childNode : reasoner.getSubObjectProperties(property, true)) {
            printObjectProperty(reasoner, childNode.getRepresentativeElement());
        }

    }

    /**
     * Helper class for printObjectProperty
     *
     * @param property
     * @param domainClazzName
     * @param rangeClasses
     */
    private void printObjectPropertyRanges(OWLObjectPropertyExpression property, String domainClazzName, Set<OWLClassExpression> rangeClasses) {
        if (rangeClasses.isEmpty()) {

            if (!domainClazzName.equals(UNDEFINED))
                out.println(domainClazzName + " o-- " + UNDEFINED + " : " + labelFor(property.getNamedProperty()));
            else {
                unknownCounter++;
                String newUnknownName = UNDEFINED + unknownCounter;
                out.println(newUnknownName + " o-- " + newUnknownName + " : " + labelFor(property.getNamedProperty()));
            }

        } else {
            for (OWLClassExpression rangeClazz : rangeClasses) {
                for (OWLClassExpression rangeClazz2 : rangeClazz.asDisjunctSet()) {
                    String rangeClazzName = rangeClazz2.isAnonymous() ? UNDEFINED : labelFor(rangeClazz2.asOWLClass());

                    out.println(domainClazzName + " o-- " + rangeClazzName + " : " + labelFor(property.getNamedProperty()));
                }
            }
        }
    }
}
