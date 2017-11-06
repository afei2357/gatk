package org.broadinstitute.hellbender.tools.spark.sv.discovery.prototype;

import com.esotericsoftware.kryo.DefaultSerializer;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.google.common.collect.ImmutableList;
import htsjdk.samtools.SAMSequenceDictionary;
import org.apache.logging.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.broadcast.Broadcast;
import org.broadinstitute.hellbender.engine.datasources.ReferenceMultiSource;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.AlignedContig;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.AlignmentInterval;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.ChimericAlignment;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.StrandSwitch;
import org.broadinstitute.hellbender.utils.IntervalUtils;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import scala.Tuple2;

import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This deals with the special case where a contig's multiple (> 2) alignments has head and tail mapped to the same chr.
 * For the case where the head and tail mapped to different chromosome, we could decide to emit all BND records, but
 * that could be dealt with later.
 */
final class CpxVariantDetector implements VariantDetectorFromLocalAssemblyContigAlignments {

    @Override
    public void inferSvAndWriteVCF(final String vcfOutputFileName, final String sampleId, final JavaRDD<AlignedContig> localAssemblyContigs,
                                   final Broadcast<ReferenceMultiSource> broadcastReference,
                                   final Broadcast<SAMSequenceDictionary> broadcastSequenceDictionary,
                                   final Logger toolLogger) {

        // determine event primary chromosome and strand representation
        // add jumping locations on reference annotation then extract the segmenting locations on event primary chromosome
        final JavaRDD<AnnotatedContig> annotatedContigs = localAssemblyContigs.map(tig -> new AnnotatedContig(tig, broadcastSequenceDictionary.getValue()));

        // segment affected reference regions by jumping locations
        final JavaPairRDD<String, Tuple2<List<SimpleInterval>, List<Integer>>> assemblyContigPrimarySegments =
                annotatedContigs
                        .mapToPair(annotatedContig ->
                                new Tuple2<>(annotatedContig.contig.contigName, segmentReference(annotatedContig)));

        // make sense of event, i.e. provide interpretation, and extract corresponding alt haplotype sequence

        // output VCF

        // debug
        org.broadinstitute.hellbender.tools.spark.sv.utils.FileUtils.
                writeLinesToSingleFile(
                        annotatedContigs
                                .mapToPair(annotatedContig -> new Tuple2<>(annotatedContig.contig.contigName, annotatedContig))
                                .join(assemblyContigPrimarySegments)
                                .sortByKey()
                                .values()
                                .map(tuple2 -> "\n" + tuple2._1.toString() +
                                        "\nSegments:\t" + tuple2._2._1.toString() +
                                        "\nEvents:\t" + tuple2._2._2.toString())
                                .collect().iterator(),
                        Paths.get(vcfOutputFileName).getParent().toAbsolutePath().toString() + "/cpxJumps.txt");
    }

    @DefaultSerializer(AnnotatedContig.Serializer.class)
    private static final class AnnotatedContig {
        private static final AlignedContig.Serializer contigSerializer = new AlignedContig.Serializer();
        private static final BasicInfo.Serializer basicInfoSerializer = new BasicInfo.Serializer();
        private static final Jump.Serializer jumpSerializer = new Jump.Serializer();
        final AlignedContig contig;
        final BasicInfo basicInfo;
        private List<Jump> jumps;
        private List<SimpleInterval> eventPrimaryChromosomeSegmentingLocations;

        /**
         * These two getters may return null if the corresponding fields are not properly initialized yet.
         */
        List<Jump> getJumps() {
            return jumps;
        }
        List<SimpleInterval> getEventPrimaryChromosomeSegmentingLocations() { return eventPrimaryChromosomeSegmentingLocations;}

        AnnotatedContig(final AlignedContig contig, final SAMSequenceDictionary refSequenceDictionary) {
            this.contig = new AlignedContig(contig.contigName, contig.contigSequence,
                    deOverlapAlignments(contig.alignmentIntervals, refSequenceDictionary), contig.hasEquallyGoodAlnConfigurations);;
            this.basicInfo = new BasicInfo(this.contig);
            markSegmentingRefLocations(refSequenceDictionary);
        }

        private static List<AlignmentInterval> deOverlapAlignments(final List<AlignmentInterval> originalAlignments,
                                                                   final SAMSequenceDictionary refSequenceDictionary) {
            final List<AlignmentInterval> result = new ArrayList<>(originalAlignments.size());
            final Iterator<AlignmentInterval> iterator = originalAlignments.iterator();
            AlignmentInterval one = iterator.next();
            while (iterator.hasNext()) {
                final AlignmentInterval two = iterator.next();
                // TODO: 11/5/17 an edge case is possible where the best configuration contains two alignments, one of which contains a large gap, and since the gap split happens after the configuration scoring, one of the alignment from the gap split may be contained in the other original alignment, leading to problems; here we first skip it
                if (two.alnModType.equals(AlnModType.FROM_SPLIT_GAPPED_ALIGNMENT)) {
                    final int overlapOnRead = AlignmentInterval.overlapOnContig(one, two);
                    if (overlapOnRead >= two.getSpanOnRead())
                        continue;
                }
                final List<AlignmentInterval> deoverlapped = ContigAlignmentsModifier.removeOverlap(one, two, refSequenceDictionary);
                result.add(deoverlapped.get(0));
                one = deoverlapped.get(1);
            }
            result.add(one);
            return result;
        }

        void markSegmentingRefLocations(final SAMSequenceDictionary refSequenceDictionary) {
            jumps = extractJumpsOnReference(contig.alignmentIntervals, basicInfo, refSequenceDictionary);
            eventPrimaryChromosomeSegmentingLocations =
                    extractSegmentingRefLocationsOnEventPrimaryChromosome(jumps, basicInfo, refSequenceDictionary);
        }



        @Override
        public String toString() {
            return "Contig:\t" + contig.toString() +
                    "\nBasicInfo:\t" + basicInfo.toString() +
                    "\nJumps:\t" + jumps.toString() +
                    "\nSeg.Boundaries:\t" + eventPrimaryChromosomeSegmentingLocations.toString();
        }

        AnnotatedContig(final Kryo kryo, final Input input) {
            contig = contigSerializer.read(kryo, input, AlignedContig.class);
            basicInfo = basicInfoSerializer.read(kryo, input, BasicInfo.class);
            final int numJumps = input.readInt();
            jumps = new ArrayList<>(numJumps);
            for (int i = 0; i < numJumps; ++i) {
                jumps.add(jumpSerializer.read(kryo, input, Jump.class));
            }
            final int numSegmentingLocs = input.readInt();
            eventPrimaryChromosomeSegmentingLocations = new ArrayList<>(numJumps);
            for (int i = 0; i < numSegmentingLocs; ++i) {
                eventPrimaryChromosomeSegmentingLocations.add(kryo.readObject(input, SimpleInterval.class));
            }
        }

        void serialize(final Kryo kryo, final Output output) {
            contigSerializer.write(kryo, output, contig);
            basicInfoSerializer.write(kryo, output, basicInfo);
            output.writeInt(jumps.size());
            for (final Jump jump : jumps)
                jumpSerializer.write(kryo, output, jump);
            output.writeInt(eventPrimaryChromosomeSegmentingLocations.size());
            for (final SimpleInterval segmentingLoc : eventPrimaryChromosomeSegmentingLocations)
                kryo.writeObject(output, segmentingLoc);
        }

        public static final class Serializer extends com.esotericsoftware.kryo.Serializer<AnnotatedContig> {
            @Override
            public void write(final Kryo kryo, final Output output, final AnnotatedContig alignedContig) {
                alignedContig.serialize(kryo, output);
            }

            @Override
            public AnnotatedContig read(final Kryo kryo, final Input input, final Class<AnnotatedContig> clazz) {
                return new AnnotatedContig(kryo, input);
            }
        }
    }

    // =================================================================================================================

    @DefaultSerializer(BasicInfo.Serializer.class)
    private static final class BasicInfo {

        final String eventPrimaryChromosome; // where the head and tail alignments are mapped to, mappings to other chromosomes of a particular assembly contig will be considered insertion/MEI
        final boolean forwardStrandRep;     // if the signaling assembly contig is a forward strand representation
        final SimpleInterval alpha;         // the starting ref location of the head/tail alignment if the signaling assembly contig is a '+'/'-' representation
        final SimpleInterval omega;         // the ending   ref location of the tail/head alignment if the signaling assembly contig is a '+'/'-' representation

        BasicInfo(final AlignedContig contig) {
            final AlignmentInterval head = contig.alignmentIntervals.get(0),
                                    tail = contig.alignmentIntervals.get(contig.alignmentIntervals.size()-1);

            eventPrimaryChromosome = head.referenceSpan.getContig();
            forwardStrandRep = head.forwardStrand;
            if (forwardStrandRep) {
                alpha = new SimpleInterval(head.referenceSpan.getContig(), head.referenceSpan.getStart(), head.referenceSpan.getStart());
                omega = new SimpleInterval(tail.referenceSpan.getContig(), tail.referenceSpan.getEnd(), tail.referenceSpan.getEnd());
            } else {
                alpha = new SimpleInterval(tail.referenceSpan.getContig(), tail.referenceSpan.getStart(), tail.referenceSpan.getStart());
                omega = new SimpleInterval(head.referenceSpan.getContig(), head.referenceSpan.getEnd(), head.referenceSpan.getEnd());
            }
        }

        @Override
        public String toString() {
            return "primary chr: " + eventPrimaryChromosome + "\tstrand rep:" + (forwardStrandRep ? '+' : '-') +
                    "\talpha: " + alpha.toString() +  "\tomega: " + omega.toString();
        }

        BasicInfo(final Kryo kryo, final Input input) {
            eventPrimaryChromosome = input.readString();
            forwardStrandRep = input.readBoolean();
            alpha = kryo.readObject(input, SimpleInterval.class);
            omega = kryo.readObject(input, SimpleInterval.class);
        }

        void serialize(final Kryo kryo, final Output output) {
            output.writeString(eventPrimaryChromosome);
            output.writeBoolean(forwardStrandRep);
            kryo.writeObject(output, alpha/*, simpleIntervalSerializer*/);
            kryo.writeObject(output, omega/*, simpleIntervalSerializer*/);
        }

        public static final class Serializer extends com.esotericsoftware.kryo.Serializer<BasicInfo> {
            @Override
            public void write(final Kryo kryo, final Output output, final BasicInfo alignedContig) {
                alignedContig.serialize(kryo, output);
            }

            @Override
            public BasicInfo read(final Kryo kryo, final Input input, final Class<BasicInfo> clazz) {
                return new BasicInfo(kryo, input);
            }
        }
    }

    // =================================================================================================================

    /**
     * A jump has a starting and landing ref location.
     *
     * <p>
     * A jump can be:
     * <ul>
     *     <li>gapped--meaning a part of read is uncovered by neighboring AI's;</li>
     *     <li>connected--meaning neighboring--but not overlapping on the read--AI's leave no base on the read uncovered;</li>
     *     <li>retracting--meaning neighboring AI's overlap on the read, pointing to homology between their ref span</li>
     * </ul>
     * Among them, retracting jumps are the most difficult to deal with, mainly due to how to have a consistent
     * homology-yielding scheme.
     *
     * Here we DO NOT output jumps that are retracting: i.e. we enforce the homology-yielding convention as implemented in
     * {@link ContigAlignmentsModifier#homologyYieldingStrategy(AlignmentInterval, AlignmentInterval, SAMSequenceDictionary)}.
     *
     * </p>
     */
    @DefaultSerializer(Jump.Serializer.class)
    private static final class Jump {

        final SimpleInterval start;
        final SimpleInterval landing;
        final StrandSwitch strandSwitch;

        Jump(final AlignmentInterval originalOne, final AlignmentInterval originalTwo, final boolean forwardStrandRep,
             final SAMSequenceDictionary refSequenceDictionary) {
            Utils.validateArg(AlignmentInterval.overlapOnContig(originalOne, originalTwo) <=0,
                    "assumption that input alignments DO NOT overlap is violated.");

            strandSwitch = ChimericAlignment.determineStrandSwitch(originalOne, originalTwo);

            final int overlapOnRead = AlignmentInterval.overlapOnContig(originalOne, originalTwo);
            final AlignmentInterval one, two;
            if (overlapOnRead > 0) {
                final List<AlignmentInterval> developedAlignments =
                        ContigAlignmentsModifier.removeOverlap(originalOne, originalTwo, refSequenceDictionary);
                one = developedAlignments.get(0);
                two = developedAlignments.get(1);
            } else {
                one = originalOne;
                two = originalTwo;
            }

            switch (strandSwitch){
                case NO_SWITCH:
                    if (one.forwardStrand) {
                        start = new SimpleInterval(one.referenceSpan.getContig(), one.referenceSpan.getEnd(), one.referenceSpan.getEnd());
                        landing = new SimpleInterval(two.referenceSpan.getContig(), two.referenceSpan.getStart(), two.referenceSpan.getStart());
                    } else {
                        start = new SimpleInterval(one.referenceSpan.getContig(), one.referenceSpan.getStart(), one.referenceSpan.getStart());
                        landing = new SimpleInterval(two.referenceSpan.getContig(), two.referenceSpan.getEnd(), two.referenceSpan.getEnd());
                    }
                    break;
                case FORWARD_TO_REVERSE:
                    start = new SimpleInterval(one.referenceSpan.getContig(), one.referenceSpan.getEnd(), one.referenceSpan.getEnd());
                    landing = new SimpleInterval(two.referenceSpan.getContig(), two.referenceSpan.getEnd(), two.referenceSpan.getEnd());
                    break;
                case REVERSE_TO_FORWARD:
                    start = new SimpleInterval(one.referenceSpan.getContig(), one.referenceSpan.getStart(), one.referenceSpan.getStart());
                    landing = new SimpleInterval(two.referenceSpan.getContig(), two.referenceSpan.getStart(), two.referenceSpan.getStart());
                    break;
                    default: throw new NoSuchElementException("seeing a strand switch that doesn't make sense");
            }

        }

        @Override
        public String toString() {
            return "Jump start: " + start.toString() + "\tjump landing: " + landing.toString() + "\t" + strandSwitch.name();
        }

        Jump(final Kryo kryo, final Input input) {
            start = kryo.readObject(input, SimpleInterval.class);
            landing = kryo.readObject(input, SimpleInterval.class);
            strandSwitch = StrandSwitch.values()[input.readInt()];
        }

        void serialize(final Kryo kryo, final Output output) {
            kryo.writeObject(output, start);
            kryo.writeObject(output, landing);
            output.writeInt(strandSwitch.ordinal());
        }

        public static final class Serializer extends com.esotericsoftware.kryo.Serializer<Jump> {
            @Override
            public void write(final Kryo kryo, final Output output, final Jump alignedContig) {
                alignedContig.serialize(kryo, output);
            }

            @Override
            public Jump read(final Kryo kryo, final Input input, final Class<Jump> clazz) {
                return new Jump(kryo, input);
            }
        }
    }

    /**
     * Each pair of neighboring reference locations are meant to be used closed, i.e. [a, b].
     * The returned list of {@link Jump}'s are ordered along the alignments of the contig
     * if the contig is '+' strand representation, or reversed if the contig is '-' strand representation.
     */
    private static List<Jump> extractJumpsOnReference(final List<AlignmentInterval> alignmentConfiguration,
                                                      final BasicInfo basicInfo,
                                                      final SAMSequenceDictionary refSequenceDictionary) {

        final List<Jump> unsortedJumps = new ArrayList<>(alignmentConfiguration.size() - 1);
        final Iterator<AlignmentInterval> iterator = alignmentConfiguration.iterator();
        AlignmentInterval one = iterator.next();
        while(iterator.hasNext()) {
            final AlignmentInterval two = iterator.next();
            unsortedJumps.add( new Jump(one, two, basicInfo.forwardStrandRep, refSequenceDictionary) );
            one = two;
        }

        return unsortedJumps;
    }

    private static List<SimpleInterval> extractSegmentingRefLocationsOnEventPrimaryChromosome(final List<Jump> jumps,
                                                                                              final BasicInfo basicInfo,
                                                                                              final SAMSequenceDictionary refSequenceDictionary) {
        return jumps.stream()
                .flatMap(jump -> Stream.of(jump.start, jump.landing))
                .filter(loc ->
                    loc.getContig().equals(basicInfo.eventPrimaryChromosome)
                            &&
                            IntervalUtils.compareLocatables(loc, basicInfo.alpha, refSequenceDictionary) > 0
                            &&
                            IntervalUtils.compareLocatables(loc, basicInfo.omega, refSequenceDictionary) < 0
                )
                .distinct()
                .sorted((one, two) -> IntervalUtils.compareLocatables(one, two, refSequenceDictionary))
                .collect(Collectors.toList());
    }

    // =================================================================================================================

    private static Tuple2<List<SimpleInterval>, List<Integer>> segmentReference(final AnnotatedContig annotatedContig) {

        final String eventPrimaryChromosome = annotatedContig.basicInfo.eventPrimaryChromosome;
        final List<SimpleInterval> segmentingLocations = annotatedContig.getEventPrimaryChromosomeSegmentingLocations();

        // this is the case where a contig has the middle alignment mapped to some disjoint places
        // (different chromosome, or same chromosome but not in the region bounded by alpha and omega),
        // and the first and last alignment share a single base on their ref span
        // (hence we have only one jump location on the event primary chromosome)
        if (segmentingLocations.size() == 1) {
            if (annotatedContig.contig.alignmentIntervals.stream().filter(ai -> ai.referenceSpan.getContig().equals(eventPrimaryChromosome)).count() == 2) {
                final SimpleInterval singleBase = segmentingLocations.get(0);
                final SimpleInterval insertion = annotatedContig.contig.alignmentIntervals.get(1).referenceSpan;
                final List<SimpleInterval> segments = new ArrayList<>(Arrays.asList(singleBase, singleBase));
                final List<Integer> collect = new ArrayList<>(Arrays.asList(1, 1));
                return new Tuple2<>(segments, collect);
            } else {
                throw new GATKException(annotatedContig.toString());
            }
        }

        // first segment the affected reference region based on the segmenting locations
        // note that the resulting segments have a property that neighboring segments always share a boundary base
        final List<SimpleInterval> segments = new ArrayList<>(segmentingLocations.size() - 1); // minimal size 1

        final Iterator<SimpleInterval> iterator = segmentingLocations.iterator();
        SimpleInterval leftBoundary = iterator.next();
        while (iterator.hasNext()) {
            final SimpleInterval rightBoundary = iterator.next();
            segments.add(new SimpleInterval(eventPrimaryChromosome, leftBoundary.getStart(), rightBoundary.getStart()));
            leftBoundary = rightBoundary;
        }

        // then using overlap with alignments ordered along the '+' strand representation of the signaling contig to
        // make sense of how the reference segments are ordered, including orientations--using signs of the integers,
        // on the sample;
        // the absolute value in the list is 1+idx into segments
        final List<Integer> collect = new ArrayList<>();
        // N*M overlaps
        final List<AlignmentInterval> alignmentIntervalList =
                annotatedContig.basicInfo.forwardStrandRep ? annotatedContig.contig.alignmentIntervals
                                                           : ImmutableList.copyOf(annotatedContig.contig.alignmentIntervals).reverse();
        for (final AlignmentInterval alignment : alignmentIntervalList) {
            for (int i = 0; i < segments.size(); ++i) {
                final SimpleInterval segment = segments.get(i);
                if (segment.overlaps(alignment.referenceSpan) && segment.intersect(alignment.referenceSpan).size() > 1) { // avoid case where segment and alignment ref span overlap only on one boundary base
                    if (annotatedContig.basicInfo.forwardStrandRep)
                        collect.add( (alignment.forwardStrand ? 1 : -1) * (i+1) );
                    else
                        collect.add( (alignment.forwardStrand ? -1 : 1) * (i+1) );
                }
            }
        }

        return new Tuple2<>(segments, collect);
    }


    // =================================================================================================================

}
