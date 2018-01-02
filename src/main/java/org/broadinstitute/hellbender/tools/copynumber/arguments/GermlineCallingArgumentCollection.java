package org.broadinstitute.hellbender.tools.copynumber.arguments;

import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.hellbender.tools.copynumber.GermlineCNVCaller;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author Mehrtash Babadi &lt;mehrtash@broadinstitute.org&gt;
 */
public final class GermlineCallingArgumentCollection implements Serializable {
    private static final long serialVersionUID = 1L;

    @Argument(
            doc = "Prior probability of alt copy number with respect to contig baseline state in the reference copy number.",
            fullName = "p-alt",
            minValue = 0.,
            optional = true
    )
    private double pAlt = 1e-6;

    @Argument(
            doc = "Prior probability of treating an interval as CNV-active",
            fullName = "p-active",
            minValue = 0.,
            optional = true
    )
    private double pActive = 1e-2;

    @Argument(
            doc = "Coherence length of CNV events (in the units of bp).",
            fullName = "cnv-coherence-length",
            minValue = 0.,
            optional = true
    )
    private double cnvCoherenceLength = 10000.0;

    @Argument(
            doc = "Coherence length of copy number classes (in the units of bp).",
            fullName = "class-coherence-length",
            minValue = 0.,
            optional = true
    )
    private double classCoherenceLength = 10000.0;

    @Argument(
            doc = "Highest considered copy number.",
            fullName = "max-copy-number",
            minValue = 0,
            optional = true
    )
    private int maxCopyNumber = 5;

    public List<String> generatePythonArguments(final GermlineCNVCaller.RunMode runMode) {
        final List<String> arguments = new ArrayList<>(Arrays.asList(
                String.format("--p_alt=%e", pAlt),
                String.format("--cnv_coherence_length=%e", cnvCoherenceLength),
                String.format("--max_copy_number=%d", maxCopyNumber)));
        if (runMode == GermlineCNVCaller.RunMode.COHORT) {
            arguments.addAll(Arrays.asList(
                    String.format("--p_active=%f", pActive),
                    String.format("--class_coherence_length=%f", classCoherenceLength)));
        }
        return arguments;
    }

    /* todo */
    public void validate() {

    }
}
