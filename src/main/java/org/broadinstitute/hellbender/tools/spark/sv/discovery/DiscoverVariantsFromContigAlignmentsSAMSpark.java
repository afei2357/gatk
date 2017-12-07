package org.broadinstitute.hellbender.tools.spark.sv.discovery;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.util.SequenceUtil;
import htsjdk.variant.variantcontext.VariantContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.ArgumentCollection;
import org.broadinstitute.barclay.argparser.BetaFeature;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.StructuralVariantDiscoveryProgramGroup;
import org.broadinstitute.hellbender.engine.datasources.ReferenceMultiSource;
import org.broadinstitute.hellbender.engine.filters.ReadFilter;
import org.broadinstitute.hellbender.engine.filters.ReadFilterLibrary;
import org.broadinstitute.hellbender.engine.spark.GATKSparkTool;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.tools.spark.sv.evidence.EvidenceTargetLink;
import org.broadinstitute.hellbender.tools.spark.sv.evidence.ReadMetadata;
import org.broadinstitute.hellbender.tools.spark.sv.utils.*;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import scala.Tuple2;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.broadinstitute.hellbender.tools.spark.sv.StructuralVariationDiscoveryArgumentCollection.DiscoverVariantsFromContigsAlignmentsSparkArgumentCollection;
import static org.broadinstitute.hellbender.tools.spark.sv.StructuralVariationDiscoveryArgumentCollection.DiscoverVariantsFromContigsAlignmentsSparkArgumentCollection.*;

/**
 * (Internal) Examines aligned contigs from local assemblies and calls structural variants
 *
 * <p>This tool is used in development and should not be of interest to most researchers.  It packages structural
 * variant calling as a separate tool, independent of the generation of local assemblies.
 * Most researchers will run StructuralVariationDiscoveryPipelineSpark, which both generates local assemblies
 * of interesting genomic regions, and then calls structural variants from these assemblies.</p>
 * <p>This tool takes a SAM/BAM/CRAM containing the alignments of assembled contigs from local assemblies
 * and searches it for split alignments indicating the presence of structural variations. To do so the tool parses
 * primary and supplementary alignments; secondary alignments are ignored. To be considered valid evidence of an SV,
 * two alignments from the same contig must have mapping quality 60, and both alignments must have length greater than
 * or equal to min-alignment-length. Imprecise variants with approximate locations are also called.</p>
 * <p>The input file is typically the output file produced by FindBreakpointEvidenceSpark.</p>
 *
 * <h3>Inputs</h3>
 * <ul>
 *     <li>An input file of assembled contigs or long reads aligned to reference.</li>
 *     <li>The reference to which the contigs have been aligned.</li>
 * </ul>
 *
 * <h3>Output</h3>
 * <ul>
 *     <li>A vcf file describing the discovered structural variants.</li>
 * </ul>
 *
 * <h3>Usage example</h3>
 * <pre>
 *   gatk DiscoverVariantsFromContigAlignmentsSAMSpark \
 *     -I assemblies.sam \
 *     -R reference.2bit \
 *     -O structural_variants.vcf
 * </pre>
 * <p>This tool can be run without explicitly specifying Spark options. That is to say, the given example command
 * without Spark options will run locally. See
 * <a href ="https://software.broadinstitute.org/gatk/documentation/article?id=10060">Tutorial#10060</a>
 * for an example of how to set up and run a Spark tool on a cloud Spark cluster.</p>
 *
 * <h3>Notes</h3>
 * <p>The reference is broadcast by Spark, and must therefore be a .2bit file due to current restrictions.</p>
 */
@DocumentedFeature
@BetaFeature
@CommandLineProgramProperties(
        oneLineSummary = "(Internal) Examines aligned contigs from local assemblies and calls structural variants",
        summary =
        "This tool is used in development and should not be of interest to most researchers.  It packages structural" +
        " variant calling as a separate tool, independent of the generation of local assemblies." +
        " Most researchers will run StructuralVariationDiscoveryPipelineSpark, which both generates local assemblies" +
        " of interesting genomic regions, and then calls structural variants from these assemblies." +
        " This tool takes a SAM/BAM/CRAM containing the alignments of assembled contigs from local assemblies" +
        " and searches it for split alignments indicating the presence of structural variations. To do so the tool parses" +
        " primary and supplementary alignments; secondary alignments are ignored. To be considered valid evidence of an SV," +
        " two alignments from the same contig must have mapping quality 60, and both alignments must have length greater than" +
        " or equal to min-alignment-length. Imprecise variants with approximate locations are also called.\n" +
        " The input file is typically the output file produced by FindBreakpointEvidenceSpark.",
        programGroup = StructuralVariantDiscoveryProgramGroup.class)
public final class DiscoverVariantsFromContigAlignmentsSAMSpark extends GATKSparkTool {
    private static final long serialVersionUID = 1L;
    private final Logger localLogger = LogManager.getLogger(DiscoverVariantsFromContigAlignmentsSAMSpark.class);

    @ArgumentCollection
    private final DiscoverVariantsFromContigsAlignmentsSparkArgumentCollection discoverStageArgs =
            new DiscoverVariantsFromContigsAlignmentsSparkArgumentCollection();

    @Argument(doc = "sam file for aligned contigs", shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME)
    private String vcfOutputFileName;

    @Override
    public boolean requiresReference() {
        return true;
    }

    @Override
    public boolean requiresReads() {
        return true;
    }

    @Override
    public ReadFilter makeReadFilter() {
        return ReadFilterLibrary.MAPPED;
    }

    @Override
    protected void runTool(final JavaSparkContext ctx) {

        final SvDiscoveryDataBundle svDiscoveryDataBundle =
                new SvDiscoveryDataBundle(ctx, discoverStageArgs, vcfOutputFileName, null, null,
                        null,
                        getReads(), getHeaderForReads(), getReference(), localLogger);

        final JavaRDD<AlignedContig> parsedContigAlignments =
                new SAMFormattedContigAlignmentParser(svDiscoveryDataBundle.reads, svDiscoveryDataBundle.headerBroadcast.getValue(), true)
                        .getAlignedContigs();

        discoverVariantsAndWriteVCF(svDiscoveryDataBundle, parsedContigAlignments);
    }

    public static final class SAMFormattedContigAlignmentParser extends AlignedContigGenerator implements Serializable {
        private static final long serialVersionUID = 1L;

        private final JavaRDD<GATKRead> unfilteredContigAlignments;
        private final SAMFileHeader header;
        private final boolean splitGapped;

        public SAMFormattedContigAlignmentParser(final JavaRDD<GATKRead> unfilteredContigAlignments,
                                                 final SAMFileHeader header, final boolean splitGapped) {
            this.unfilteredContigAlignments = unfilteredContigAlignments;
            this.header = header;
            this.splitGapped = splitGapped;
        }

        @Override
        public JavaRDD<AlignedContig> getAlignedContigs() {
            return unfilteredContigAlignments
                    .filter(r -> !r.isSecondaryAlignment())
                    .groupBy(GATKRead::getName)
                    .map(Tuple2::_2)
                    .map(gatkReads ->
                            parseReadsAndOptionallySplitGappedAlignments(
                                    Utils.stream(gatkReads).map(r->r.convertToSAMRecord(header)).collect(Collectors.toList()),
                                    GAPPED_ALIGNMENT_BREAK_DEFAULT_SENSITIVITY, splitGapped));
        }

        /**
         * Iterates through the input {@code noSecondaryAlignments}, which are assumed to contain no secondary alignment (i.e. records with "XA" tag),
         * converts to custom {@link AlignmentInterval} format and
         * split the records when the gap in the alignment reaches the specified {@code sensitivity}.
         * The size of the returned iterable of {@link AlignmentInterval}'s is guaranteed to be no lower than that of the input iterable.
         */
        @VisibleForTesting
        public static AlignedContig parseReadsAndOptionallySplitGappedAlignments(final Iterable<SAMRecord> noSecondaryAlignments,
                                                                                 final int gapSplitSensitivity,
                                                                                 final boolean splitGapped) {

            Utils.validateArg(noSecondaryAlignments.iterator().hasNext(), "input collection of GATK reads is empty");

            final SAMRecord primaryAlignment
                    = Utils.stream(noSecondaryAlignments).filter(sam -> !sam.getSupplementaryAlignmentFlag())
                    .findFirst()
                    .orElseThrow(() -> new GATKException("no primary alignment for read " + noSecondaryAlignments.iterator().next().getReadName()));

            Utils.validate(!primaryAlignment.getCigar().containsOperator(CigarOperator.H),
                    "assumption that primary alignment does not contain hard clipping is invalid for read: " + primaryAlignment.toString());

            final byte[] contigSequence = primaryAlignment.getReadBases().clone();
            final List<AlignmentInterval> parsedAlignments;
            if ( primaryAlignment.getReadUnmappedFlag() ) { // the Cigar
                parsedAlignments = Collections.emptyList();
            } else {
                if (primaryAlignment.getReadNegativeStrandFlag()) {
                    SequenceUtil.reverseComplement(contigSequence);
                }

                final Stream<AlignmentInterval> unSplitAIList = Utils.stream(noSecondaryAlignments).map(AlignmentInterval::new);
                if (splitGapped) {
                    final int unClippedContigLength = primaryAlignment.getReadLength();
                    parsedAlignments = unSplitAIList.map(ar ->
                            GappedAlignmentSplitter.split(ar, gapSplitSensitivity, unClippedContigLength))
                            .flatMap(Utils::stream).collect(Collectors.toList());
                } else {
                    parsedAlignments = unSplitAIList.collect(Collectors.toList());
                }
            }
            return new AlignedContig(primaryAlignment.getReadName(), contigSequence, parsedAlignments, false);
        }
    }

    /**
     * Makes sense out of the alignment records of the locally assembled contigs,
     * turn into annotated {@link VariantContext}'s, and writes them to VCF.
     */
    public static void discoverVariantsAndWriteVCF(final SvDiscoveryDataBundle svDiscoveryDataBundle,
                                                   final JavaRDD<AlignedContig> alignedContigs) {


        final JavaPairRDD<NovelAdjacencyReferenceLocations, Iterable<ChimericAlignment>> narlsAndSources =
                detectNarls(svDiscoveryDataBundle, alignedContigs);

        List<VariantContext> annotatedVariants =
                inferTypeAndAnnotateByAlignmentSignatures(narlsAndSources, svDiscoveryDataBundle.cnvCallsBroadcast,
                        svDiscoveryDataBundle.sampleId, svDiscoveryDataBundle.referenceBroadcast,
                        svDiscoveryDataBundle.referenceSequenceDictionaryBroadcast);

        narlsAndSources.unpersist();

        annotatedVariants = detectImpreciseVariantsAndAddReadAnnotations(svDiscoveryDataBundle, annotatedVariants);

        SVVCFWriter.writeVCF(annotatedVariants,
                svDiscoveryDataBundle.outputPath,
                svDiscoveryDataBundle.referenceSequenceDictionaryBroadcast.getValue(),
                svDiscoveryDataBundle.toolLogger);
    }

    //==================================================================================================================

    private static JavaPairRDD<NovelAdjacencyReferenceLocations, Iterable<ChimericAlignment>> detectNarls(final SvDiscoveryDataBundle svDiscoveryDataBundle,
                                                                                                          final JavaRDD<AlignedContig> alignedContigs) {
        final List<SVInterval> assembledIntervals = svDiscoveryDataBundle.assembledIntervals;
        final Broadcast<SAMSequenceDictionary> referenceSequenceDictionaryBroadcast = svDiscoveryDataBundle.referenceSequenceDictionaryBroadcast;
        final DiscoverVariantsFromContigsAlignmentsSparkArgumentCollection parameters = svDiscoveryDataBundle.discoverStageArgs;
        final Logger toolLogger = svDiscoveryDataBundle.toolLogger;

        final JavaPairRDD<NovelAdjacencyReferenceLocations, Iterable<ChimericAlignment>> narlsAndSources =
                alignedContigs.filter(alignedContig -> alignedContig.alignmentIntervals.size()>1)                                     // filter out any contigs that has less than two alignment records
                        .mapToPair(alignedContig -> new Tuple2<>(alignedContig.contigSequence,                                        // filter a contig's alignment and massage into ordered collection of chimeric alignments
                                ChimericAlignment.parseOneContig(alignedContig, referenceSequenceDictionaryBroadcast.getValue(),
                                        true, DEFAULT_MIN_ALIGNMENT_LENGTH, CHIMERIC_ALIGNMENTS_HIGHMQ_THRESHOLD, true)))
                        .flatMapToPair(pair -> discoverNovelAdjacencyFromChimericAlignments(pair, referenceSequenceDictionaryBroadcast.getValue()))       // a filter-passing contig's alignments may or may not produce novel adjacency
                        .groupByKey();                                                                                                // group the same novel adjacency produced by different contigs together

        narlsAndSources.cache();

        if ( parameters.truthVCF != null ) {
            final SAMSequenceDictionary sequenceDictionary = referenceSequenceDictionaryBroadcast.getValue();
            final SVIntervalTree<String> trueBreakpoints =
                    SVVCFReader.readBreakpointsFromTruthVCF(parameters.truthVCF, sequenceDictionary, parameters.truthIntervalPadding);

            if ( assembledIntervals != null ) {
                evaluateIntervalsAgainstTruth(assembledIntervals, trueBreakpoints, toolLogger);
            }

            final SVIntervalTree<String> narlyBreakpoints =
                    readBreakpointsFromNarls(narlsAndSources.map(Tuple2::_1).collect(), sequenceDictionary, parameters.truthIntervalPadding);

            evaluateNarlsAgainstTruth(narlyBreakpoints, trueBreakpoints, toolLogger);
        }

        return narlsAndSources;
    }

    // TODO: 7/6/17 interface to be changed in the new implementation, where one contig produces a set of NARL's.
    /**
     * Given contig alignments, emit novel adjacency not present on the reference to which the locally-assembled contigs were aligned.
     */
    public static Iterator<Tuple2<NovelAdjacencyReferenceLocations, ChimericAlignment>>
    discoverNovelAdjacencyFromChimericAlignments(final Tuple2<byte[], List<ChimericAlignment>> tigSeqAndChimeras, final SAMSequenceDictionary referenceDictionary) {
        return Utils.stream(tigSeqAndChimeras._2)
                .map(ca -> new Tuple2<>(new NovelAdjacencyReferenceLocations(ca, tigSeqAndChimeras._1, referenceDictionary), ca)).iterator();
    }

    public static SVIntervalTree<String> readBreakpointsFromNarls( final List<NovelAdjacencyReferenceLocations> narls,
                                                                   final SAMSequenceDictionary dictionary,
                                                                   final int breakpointPadding ) {
        final SVIntervalTree<String> breakpoints = new SVIntervalTree<>();
        for ( final NovelAdjacencyReferenceLocations narl : narls ) {
            final int padding = breakpointPadding + narl.complication.getLength();

            final SimpleInterval si1 = narl.leftJustifiedLeftRefLoc;
            breakpoints.put(
                    new SVInterval(dictionary.getSequenceIndex(si1.getContig()), si1.getStart()-padding, si1.getStart()+padding), null);

            final SimpleInterval si2 = narl.leftJustifiedRightRefLoc;
            breakpoints.put(
                    new SVInterval(dictionary.getSequenceIndex(si2.getContig()), si2.getStart()-padding, si2.getStart()+padding), null);
        }
        return breakpoints;
    }

    public static void evaluateNarlsAgainstTruth( final SVIntervalTree<String> narlyBreakpoints,
                                                  final SVIntervalTree<String> trueBreakpoints,
                                                  final Logger localLogger ) {
        final float falsePos = 1.f - narlyBreakpoints.overlapFraction(trueBreakpoints);
        final int nNarly = narlyBreakpoints.size();
        localLogger.info("Breakpoint false positive rate = " + falsePos + " (" + Math.round(falsePos*nNarly) + "/" + nNarly + ")");
        final float falseNeg = 1.f - trueBreakpoints.overlapFraction(narlyBreakpoints);
        final int nTrue = trueBreakpoints.size();
        localLogger.info("Breakpoint false negative rate = " + falseNeg + " (" + Math.round(falseNeg*nTrue) + "/" + nTrue + ")");
    }

    public static void evaluateIntervalsAgainstTruth( final List<SVInterval> assembledIntervals,
                                                      final SVIntervalTree<String> trueBreakpoints,
                                                      final Logger localLogger ) {
        final SVIntervalTree<Integer> intervals = new SVIntervalTree<>();
        final int nIntervals = assembledIntervals.size();
        for ( int idx = 0; idx != nIntervals; ++idx ) {
            intervals.put(assembledIntervals.get(idx), idx);
        }
        final float falsePos = 1.f - intervals.overlapFraction(trueBreakpoints);
        localLogger.info("Interval false positive rate = " + falsePos + " (" + Math.round(falsePos*nIntervals) + "/" + nIntervals + ")");
        final float falseNeg = 1.f - trueBreakpoints.overlapFraction(intervals);
        final int nTrue = trueBreakpoints.size();
        localLogger.info("Interval false negative rate = " + falseNeg + " (" + Math.round(falseNeg*nTrue) + "/" + nTrue + ")");
    }

    //==================================================================================================================

    private static List<VariantContext> inferTypeAndAnnotateByAlignmentSignatures(final JavaPairRDD<NovelAdjacencyReferenceLocations, Iterable<ChimericAlignment>> narlsAndSources,
                                                                                  final Broadcast<SVIntervalTree<VariantContext>> cnvCallsBroadcast,
                                                                                  final String sampleId,
                                                                                  final Broadcast<ReferenceMultiSource> referenceBroadcast,
                                                                                  final Broadcast<SAMSequenceDictionary> referenceSequenceDictionaryBroadcast) {

        return narlsAndSources
                .mapToPair(noveltyAndEvidence -> inferType(noveltyAndEvidence._1, noveltyAndEvidence._2))                     // type inference based on novel adjacency and evidence alignments
                .map(noveltyTypeAndEvidence ->
                        annotateVariant(                                                                                      // annotate the novel adjacency and inferred type
                                noveltyTypeAndEvidence._1,
                                noveltyTypeAndEvidence._2._1,
                                null,
                                noveltyTypeAndEvidence._2._2,
                                referenceBroadcast,
                                referenceSequenceDictionaryBroadcast,
                                cnvCallsBroadcast,
                                sampleId))
                .collect();
    }

    // TODO: 7/6/17 interface to be changed in the new implementation, where a set of NARL's associated with a single contig is considered together.
    /**
     * Given input novel adjacency and evidence chimeric alignments, infer type of variant.
     */
    public static Tuple2<NovelAdjacencyReferenceLocations, Tuple2<SvType, Iterable<ChimericAlignment>>> inferType(
            final NovelAdjacencyReferenceLocations novelAdjacency,
            final Iterable<ChimericAlignment> chimericAlignments) {
        return new Tuple2<>(novelAdjacency,
                new Tuple2<>(SvTypeInference.inferFromNovelAdjacency(novelAdjacency), chimericAlignments));
    }


    /**
     * Produces annotated variant as described in {@link GATKSVVCFHeaderLines}, given input arguments.
     */
    public static VariantContext annotateVariant(final NovelAdjacencyReferenceLocations novelAdjacency,
                                                 final SvType inferredType,
                                                 final byte[] altHaplotypeSeq,
                                                 final Iterable<ChimericAlignment> chimericAlignments,
                                                 final Broadcast<ReferenceMultiSource> broadcastReference,
                                                 final Broadcast<SAMSequenceDictionary> broadcastSequenceDictionary,
                                                 final Broadcast<SVIntervalTree<VariantContext>> broadcastCNVCalls,
                                                 final String sampleId)
            throws IOException {
        return AnnotatedVariantProducer
                .produceAnnotatedVcFromInferredTypeAndRefLocations(novelAdjacency.leftJustifiedLeftRefLoc,
                        novelAdjacency.leftJustifiedRightRefLoc.getStart(), novelAdjacency.complication,
                        inferredType, altHaplotypeSeq, chimericAlignments,
                        broadcastReference, broadcastSequenceDictionary, broadcastCNVCalls, sampleId);
    }

    //==================================================================================================================

    private static List<VariantContext> detectImpreciseVariantsAndAddReadAnnotations(final SvDiscoveryDataBundle svDiscoveryDataBundle,
                                                                                     List<VariantContext> annotatedVariants) {
        final PairedStrandedIntervalTree<EvidenceTargetLink> evidenceTargetLinks = svDiscoveryDataBundle.evidenceTargetLinks;

        if (evidenceTargetLinks != null) {
            annotatedVariants = processEvidenceTargetLinks(
                    annotatedVariants,
                    evidenceTargetLinks,
                    svDiscoveryDataBundle.metadata,
                    svDiscoveryDataBundle.referenceBroadcast.getValue(),
                    svDiscoveryDataBundle.discoverStageArgs,
                    svDiscoveryDataBundle.toolLogger);
        }
        return annotatedVariants;
    }

    /**
     * Uses the input EvidenceTargetLinks to either annotate the variants called from assembly discovery with split
     * read and read pair evidence, or to call new imprecise variants if the number of pieces of evidence exceeds
     * a given threshold.
     */
    @VisibleForTesting
    static List<VariantContext> processEvidenceTargetLinks(final List<VariantContext> assemblyDiscoveredVariants,
                                                           final PairedStrandedIntervalTree<EvidenceTargetLink> evidenceTargetLinks,
                                                           final ReadMetadata metadata,
                                                           final ReferenceMultiSource reference,
                                                           final DiscoverVariantsFromContigsAlignmentsSparkArgumentCollection parameters,
                                                           final Logger localLogger) {
        final int originalEvidenceLinkSize = evidenceTargetLinks.size();
        final List<VariantContext> result = assemblyDiscoveredVariants
                .stream()
                .map(variant -> AnnotatedVariantProducer.annotateWithImpreciseEvidenceLinks(
                        variant,
                        evidenceTargetLinks,
                        reference.getReferenceSequenceDictionary(null),
                        metadata, parameters.assemblyImpreciseEvidenceOverlapUncertainty))
                        .collect(Collectors.toList());
        localLogger.info("Used " + (originalEvidenceLinkSize - evidenceTargetLinks.size()) + " evidence target links to annotate assembled breakpoints");

        final List<VariantContext> impreciseVariants =
                Utils.stream(evidenceTargetLinks)
                        .map(p -> p._2)
                        .filter(EvidenceTargetLink::isImpreciseDeletion)
                        .filter(e -> e.getReadPairs() + e.getSplitReads() > parameters.impreciseEvidenceVariantCallingThreshold)
                        .map(e -> createImpreciseDeletionVariant(e, metadata, reference))
                        .collect(Collectors.toList());

        localLogger.info("Called " + impreciseVariants.size() + " imprecise deletion variants");
        result.addAll(impreciseVariants);
        return result;
    }

    private static VariantContext createImpreciseDeletionVariant(final EvidenceTargetLink e,
                                                                 final ReadMetadata metadata,
                                                                 final ReferenceMultiSource reference) {
        final SvType svType = new SimpleSVType.ImpreciseDeletion(e, metadata);
        return AnnotatedVariantProducer
                .produceAnnotatedVcFromEvidenceTargetLink(e, svType, metadata, reference);
    }
}
