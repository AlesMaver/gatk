package org.broadinstitute.hellbender.tools.walkers.sv;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.util.IntervalTree;
import htsjdk.variant.variantcontext.StructuralVariantType;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.BetaFeature;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.StructuralVariantDiscoveryProgramGroup;
import org.broadinstitute.hellbender.engine.*;
import org.broadinstitute.hellbender.tools.sv.SVCallRecord;
import org.broadinstitute.hellbender.tools.sv.SVCallRecordUtils;
import org.broadinstitute.hellbender.utils.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Select a subset of records from a structural variant (SV) VCF generated by {@link SVPreprocessRecords}. This tool is distinct from
 * {@link org.broadinstitute.hellbender.tools.walkers.variantutils.SelectVariants} in that intervals specified with
 * -L and -XL behave differently. Rather than retrieving only records based on their positions as indicated by the
 * index file, all variants are retrieved and then filtered based on the following criteria:
 * <ul>
 *     <li>
 *         minimum overlap fraction with the intervals
 *     </li>
 *     <li>
 *         both endpoints must be contained in the intervals (optional)
 *     </li>
 *     <li>
 *         minimum variant length (all interchromosomal records are retained)
 *     </li>
 * </ul>
 *
 * Output can be restricted to or exclusive of variants only supported by depth-based algorithms ("depth-only").
 *
 * <h3>Inputs</h3>
 *
 * <ul>
 *     <li>
 *         A VCF containing only structural variant records
 *     </li>
 * </ul>
 *
 * <h3>Output</h3>
 *
 * <ul>
 *     <li>
 *         The input VCF with filtered records removed
 *     </li>
 * </ul>
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 *     gatk SVSelectVariants \
 *       -I intervals.intervals_list \
 *       -V variants.vcf.gz \
 *       -O filtered.vcf.gz
 * </pre>
 *
 * @author Mark Walker &lt;markw@broadinstitute.org&gt;
 */

@CommandLineProgramProperties(
        summary = "Annotates structural variants with whether they overlap a given set of intervals",
        oneLineSummary = "Annotates structural variants with whether they overlap a given set of intervals",
        programGroup = StructuralVariantDiscoveryProgramGroup.class
)
@BetaFeature
@DocumentedFeature
public final class SVAnnotateOverlappingRegions extends VariantWalker {
    public static final String REGIONS_FILE_LONG_NAME = "region-file";
    public static final String REGIONS_NAME_LONG_NAME = "region-name";
    public static final String REGIONS_SET_RULE_LONG_NAME = "region-set-rule";
    public static final String REGIONS_MERGING_RULE_LONG_NAME = "region-merging-rule";
    public static final String REGION_PADDING_LONG_NAME = "region-padding";
    public static final String REQUIRE_BREAKEND_OVERLAP_LONG_NAME = "require-breakend-overlap";

    @Argument(
            doc = "Output VCF",
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME
    )
    private GATKPath outputFile;

    @Argument(
            doc = "Region interval files, may be specified multiple times",
            fullName = REGIONS_FILE_LONG_NAME
    )
    private List<GATKPath> regionPaths;

    @Argument(
            doc = "Region names. All values must be unique after converting to upper-case and must correspond with the " +
                    "input order of --" + REGIONS_FILE_LONG_NAME,
            fullName = REGIONS_NAME_LONG_NAME
    )
    private List<String> regionNames;

    @Argument(
            doc = "Region interval set rule",
            fullName = REGIONS_SET_RULE_LONG_NAME,
            optional=true
    )
    private IntervalSetRule intervalSetRule = IntervalSetRule.UNION;

    @Argument(
            doc = "Region interval merging rule",
            fullName = REGIONS_MERGING_RULE_LONG_NAME,
            optional=true
    )
    private IntervalMergingRule intervalMergingRule = IntervalMergingRule.OVERLAPPING_ONLY;

    @Argument(
            doc = "Region padding (bp)",
            fullName = REGION_PADDING_LONG_NAME,
            optional=true
    )
    private int regionPadding = 0;

    @Argument(
            doc = "Require both ends of the variant to be included in the region",
            fullName = REQUIRE_BREAKEND_OVERLAP_LONG_NAME,
            optional = true
    )
    private boolean requireBreakendOverlap = false;

    private SAMSequenceDictionary dictionary;
    private List<String> formattedRegionNames;
    private final Map<String, Map<String,IntervalTree<Object>>> includedIntervalsTreeMapMap = new HashMap<>();
    private VariantContextWriter writer;

    @Override
    public void onTraversalStart() {
        Utils.validateArg(!intervalArgumentCollection.intervalsSpecified(),
                "Arguments -L and -XL are not supported, use --" + REGIONS_FILE_LONG_NAME + " instead");
        Utils.validateArg(regionPaths.size() == regionNames.size(),
                "Number of --" + REGIONS_NAME_LONG_NAME + " and --" + REGIONS_FILE_LONG_NAME + " arguments must be equal");
        dictionary = getHeaderForVariants().getSequenceDictionary();
        Utils.validateArg(dictionary != null, "Sequence dictionary not found in variants header");
        formattedRegionNames = regionNames.stream().map(String::toUpperCase).collect(Collectors.toList());
        Utils.validateArg(new HashSet<>(formattedRegionNames).size() == formattedRegionNames.size(), "Found duplicate region names (not case-sensitive)");
        for (int i = 0; i < regionPaths.size(); i++) {
            includedIntervalsTreeMapMap.put(formattedRegionNames.get(i), loadIntervalTree(regionPaths.get(i)));
        }
        writer = createVCFWriter(outputFile);
        writeVCFHeader();
    }

    @Override
    public Object onTraversalSuccess() {
        writer.close();
        return null;
    }

    private Map<String, IntervalTree<Object>> loadIntervalTree(final GATKPath path) {
        final GenomeLocParser parser = new GenomeLocParser(dictionary);
        final GenomeLocSortedSet includeSet = IntervalUtils.loadIntervals(Collections.singletonList(path.toString()), intervalSetRule, intervalMergingRule, regionPadding, parser);
        final List<SimpleInterval> intervals = IntervalUtils.convertGenomeLocsToSimpleIntervals(includeSet.toList());
        Utils.validate(!intervals.isEmpty(), "Resulting intervals are empty");
        final Map<String, IntervalTree<Object>> includedIntervalsTreeMap = new HashMap<>();
        for (final SimpleInterval interval : intervals) {
            includedIntervalsTreeMap.putIfAbsent(interval.getContig(), new IntervalTree<>());
            includedIntervalsTreeMap.get(interval.getContig()).put(interval.getStart(), interval.getEnd(), null);
        }
        return includedIntervalsTreeMap;
    }

    @Override
    public void apply(final VariantContext variant, final ReadsContext readsContext,
                      final ReferenceContext referenceContext, final FeatureContext featureContext) {
        SVCallRecord record = SVCallRecordUtils.create(variant);
        final VariantContextBuilder builder = new VariantContextBuilder(variant);
        for (final Map.Entry<String, Map<String, IntervalTree<Object>>> entry : includedIntervalsTreeMapMap.entrySet()) {
            final double overlap = intervalOverlap(record, entry.getValue());
            builder.attribute(entry.getKey(), overlap);
        }
        writer.add(builder.make());
    }

    private void writeVCFHeader() {
        final VCFHeader inputHeader = getHeaderForVariants();
        final VCFHeader header = new VCFHeader(getDefaultToolVCFHeaderLines(), inputHeader.getSampleNamesInOrder());
        for (final VCFHeaderLine line : inputHeader.getMetaDataInInputOrder()) {
            header.addMetaDataLine(line);
        }
        for (final String name : formattedRegionNames) {
            header.addMetaDataLine(new VCFInfoHeaderLine(name, 1, VCFHeaderLineType.Float, "Fraction overlap of region " + name));
        }
        writer.writeHeader(header);
    }

    @VisibleForTesting
    protected <T> double intervalOverlap(final SVCallRecord call, final Map<String, IntervalTree<T>> includedIntervalTreeMap) {
        final IntervalTree<T> startTree = includedIntervalTreeMap.get(call.getContigA());
        // Contig A included?
        if (startTree == null || startTree.size() == 0) {
            return 0;
        }
        final IntervalTree<T> endTree = includedIntervalTreeMap.get(call.getContigB());
        // Contig B included?
        if (endTree == null || endTree.size() == 0) {
            return 0;
        }
        // Breakends both included (if required)?
        final boolean overlapsA = startTree.overlappers(call.getPositionA(), call.getPositionA() + 1).hasNext();
        final boolean overlapsB = endTree.overlappers(call.getPositionB(), call.getPositionB() + 1).hasNext();
        if (requireBreakendOverlap && !(overlapsA && overlapsB)) {
            return 0;
        }
        // Require BNDs/inter-chromosomals to overlap at both ends, regardless of requireBreakendOverlap
        if (call.getType() == StructuralVariantType.BND || !call.isIntrachromosomal()) {
            if (overlapsA && overlapsB) {
                return 1;
            } else {
                return 0;
            }
        }
        // Return overlap as fraction of SVLEN
        return totalOverlap(call.getPositionA(), call.getPositionA() + call.getLength() - 1, startTree) / (double) call.getLength();
    }

    @VisibleForTesting
    protected static <T> long totalOverlap(final int start, final int end, final IntervalTree<T> tree) {
        final Iterator<IntervalTree.Node<T>> iter = tree.overlappers(start, end);
        long overlap = 0;
        while (iter.hasNext()) {
            final IntervalTree.Node<T> node = iter.next();
            overlap += intersectionLength(start, end, node.getStart(), node.getEnd());
        }
        return overlap;
    }

    @VisibleForTesting
    protected static long intersectionLength(final int start1, final int end1, final int start2, final int end2) {
        return Math.max(0, Math.min(end1, end2) - Math.max(start1, start2) + 1);
    }
}
