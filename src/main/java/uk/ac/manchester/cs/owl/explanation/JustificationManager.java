package uk.ac.manchester.cs.owl.explanation;

import org.protege.editor.core.Disposable;
import org.protege.editor.core.log.LogBanner;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owl.explanation.api.*;
import org.semanticweb.owl.explanation.impl.blackbox.checker.InconsistentOntologyExplanationGeneratorFactory;
import org.semanticweb.owl.explanation.impl.laconic.LaconicExplanationGeneratorFactory;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static uk.ac.manchester.cs.owl.explanation.ExplanationLogging.MARKER;
/*
 * Copyright (C) 2008, University of Manchester
 *
 * Modifications to the initial code base are copyright of their
 * respective authors, or their employers as appropriate.  Authorship
 * of the modifications may be determined from the ChangeLog placed at
 * the end of this file.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.

 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.

 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */

/**
 * Author: Matthew Horridge
 * The University Of Manchester
 * Information Management Group
 * Date: 03-Oct-2008
 * Manages aspects of explanation in Protege 4.
 */
public class JustificationManager implements Disposable, OWLReasonerProvider {

	private final OWLOntologyChangeListener ontologyChangeListener;

	public static final String KEY = "uk.ac.manchester.cs.owl.explanation";

	private static final Logger logger = LoggerFactory.getLogger(JustificationManager.class);

	private OWLModelManager modelManager;

	private CachingRootDerivedGenerator rootDerivedGenerator;

	private List<ExplanationManagerListener> listeners;

	private int explanationLimit;

	private boolean findAllExplanations;

	private JustificationCacheManager justificationCacheManager = new JustificationCacheManager();

	private JustificationManager(JFrame parentWindow, OWLModelManager modelManager) {
		this.modelManager = modelManager;
		rootDerivedGenerator = new CachingRootDerivedGenerator(modelManager);
		listeners = new ArrayList<>();
		explanationLimit = 2;
		findAllExplanations = true;
		ontologyChangeListener = changes -> justificationCacheManager.clear();
		modelManager.addOntologyChangeListener(ontologyChangeListener);
	}

	public OWLReasonerProvider getReasonerProvider() {
		return this;
	}

	public OWLReasonerFactory getReasonerFactory() {
		return new ProtegeOWLReasonerFactoryWrapper(modelManager.getOWLReasonerManager().getCurrentReasonerFactory());
	}

	public int getExplanationLimit() {
		return explanationLimit;
	}

	public void setExplanationLimit(int explanationLimit) {
		this.explanationLimit = explanationLimit;
		fireExplanationLimitChanged();
	}

	public boolean isFindAllExplanations() {
		return findAllExplanations;
	}

	public void setFindAllExplanations(boolean findAllExplanations) {
		this.findAllExplanations = findAllExplanations;
		fireExplanationLimitChanged();
	}

	public OWLReasoner getReasoner() {
		return modelManager.getReasoner();
	}

	/**
	 * Gets the number of explanations that have actually been computed for an
	 * entailment
	 * 
	 * @param entailment
	 *            The entailment
	 * @param type
	 *            The type of justification to be counted.
	 * @return The number of computed explanations. If no explanations have been
	 *         computed this value will be -1.
	 */
	public int getComputedExplanationCount(OWLAxiom entailment, JustificationType type) {
		JustificationCache cache = justificationCacheManager.getJustificationCache(type);
		if (cache.contains(entailment)) {
			return cache.get(entailment).size();
		} else {
			return -1;
		}
	}

	public Set<Explanation<OWLAxiom>> getJustifications(OWLAxiom entailment, JustificationType type,
			ExplanationProgressMonitor<OWLAxiom> monitor) throws ExplanationException {
//		JustificationCache cache = justificationCacheManager.getJustificationCache(type);
//		if (!cache.contains(entailment)) {
//			Set<Explanation<OWLAxiom>> expls = computeJustifications(entailment, type, monitor);
//			cache.put(expls);
//		}
//		return cache.get(entailment);
		
		return computeJustifications(entailment, type, monitor);
	}

	public Explanation<OWLAxiom> getLaconicJustification(Explanation<OWLAxiom> explanation) {
		Set<Explanation<OWLAxiom>> explanations = getLaconicExplanations(explanation, 1);
		if (explanations.isEmpty()) {
			return Explanation.getEmptyExplanation(explanation.getEntailment());
		} else {
			return explanations.iterator().next();
		}
	}

	private Set<Explanation<OWLAxiom>> computeJustifications(OWLAxiom entailment, JustificationType justificationType,
			ExplanationProgressMonitor<OWLAxiom> monitor) throws ExplanationException {
		logger.info(LogBanner.start("Computing Justifications"));
		logger.info(MARKER, "Computing justifications for {}", entailment);
		Set<OWLAxiom> axioms = new HashSet<>();
		for (OWLOntology ont : modelManager.getActiveOntologies()) {
			axioms.addAll(ont.getAxioms());
		}
		ExplanationGeneratorFactory<OWLAxiom> factory = getCurrentExplanationGeneratorFactory(justificationType, monitor);
		ExplanationGenerator<OWLAxiom> generator = factory.createExplanationGenerator(axioms, monitor);
		Set<Explanation<OWLAxiom>> explanations = generator.getExplanations(entailment);
		logger.info(MARKER, "A total of {} explanations have been computed", explanations.size());
		fireExplanationsComputed(entailment);
		logger.info(LogBanner.end());
		return explanations;
	}

	private ExplanationGeneratorFactory<OWLAxiom> getCurrentExplanationGeneratorFactory(JustificationType type,
			ExplanationProgressMonitor<OWLAxiom> monitor) {
		OWLReasoner reasoner = modelManager.getOWLReasonerManager().getCurrentReasoner();
		if (reasoner.isConsistent()) {
			if (type.equals(JustificationType.LACONIC)) {
				OWLReasonerFactory rf = getReasonerFactory();
				return ExplanationManager.createLaconicExplanationGeneratorFactory(rf, monitor);
			} else {
				OWLReasonerFactory rf = getReasonerFactory();
				return ExplanationManager.createExplanationGeneratorFactory(rf, monitor);
			}
		} else {
			if (type.equals(JustificationType.LACONIC)) {
				OWLReasonerFactory rf = getReasonerFactory();
				InconsistentOntologyExplanationGeneratorFactory fac = new InconsistentOntologyExplanationGeneratorFactory(
						rf, Long.MAX_VALUE);
				return new LaconicExplanationGeneratorFactory<>(fac);
			} else {
				OWLReasonerFactory rf = getReasonerFactory();
				return new InconsistentOntologyExplanationGeneratorFactory(rf, Long.MAX_VALUE);
			}
		}

	}

	public OWLOntologyManager getExplanationOntologyManager() {
		return modelManager.getOWLOntologyManager();
	}

	public Set<Explanation<OWLAxiom>> getLaconicExplanations(Explanation<OWLAxiom> explanation, int limit)
			throws ExplanationException {
		return computeLaconicExplanations(explanation, limit);
	}

	private Set<Explanation<OWLAxiom>> computeLaconicExplanations(Explanation<OWLAxiom> explanation, int limit)
			throws ExplanationException {
		try {
			if (modelManager.getReasoner().isConsistent()) {
				OWLReasonerFactory rf = getReasonerFactory();
				ExplanationGenerator<OWLAxiom> g = org.semanticweb.owl.explanation.api.ExplanationManager
						.createLaconicExplanationGeneratorFactory(rf)
						.createExplanationGenerator(explanation.getAxioms());
				return g.getExplanations(explanation.getEntailment(), limit);
			} else {
				OWLReasonerFactory rf = getReasonerFactory();
				InconsistentOntologyExplanationGeneratorFactory fac = new InconsistentOntologyExplanationGeneratorFactory(
						rf, Long.MAX_VALUE);
				LaconicExplanationGeneratorFactory<OWLAxiom> lacFac = new LaconicExplanationGeneratorFactory<>(fac);
				ExplanationGenerator<OWLAxiom> g = lacFac.createExplanationGenerator(explanation.getAxioms());
				return g.getExplanations(explanation.getEntailment(), limit);
			}
		} catch (ExplanationException e) {
			throw new ExplanationException(e);
		}
	}

	public void dispose() {
		rootDerivedGenerator.dispose();
		modelManager.removeOntologyChangeListener(ontologyChangeListener);
	}

	public void addListener(ExplanationManagerListener lsnr) {
		listeners.add(lsnr);
	}

	public void removeListener(ExplanationManagerListener lsnr) {
		listeners.remove(lsnr);
	}

	protected void fireExplanationLimitChanged() {
		for (ExplanationManagerListener lsnr : new ArrayList<>(listeners)) {
			lsnr.explanationLimitChanged(this);
		}
	}

	protected void fireExplanationsComputed(OWLAxiom entailment) {
		for (ExplanationManagerListener lsnr : new ArrayList<>(listeners)) {
			lsnr.explanationsComputed(entailment);
		}
	}

	public static synchronized JustificationManager getExplanationManager(JFrame parentWindow,
			OWLModelManager modelManager) {
		JustificationManager m = modelManager.get(KEY);
		if (m == null) {
			m = new JustificationManager(parentWindow, modelManager);
			modelManager.put(KEY, m);
		}
		return m;
	}
}
