package org.broadinstitute.hellbender.tools.copynumber.arguments;

import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.hellbender.tools.copynumber.DetermineGermlineContigPloidy;
import org.broadinstitute.hellbender.utils.param.ParamUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author Mehrtash Babadi &lt;mehrtash@broadinstitute.org&gt;
 */
public final class GermlineContigPloidyModelArgumentCollection implements Serializable {
    private static final long serialVersionUID = 1L;

    @Argument(
            doc = "Contig-level mean bias standard deviation.  If a single sample is provided, " +
                    "this input will be ignored.",
            fullName = "mean-bias-standard-deviation",
            minValue = 0.,
            optional = true
    )
    private double meanBiasStandardDeviation = 0.01;

    @Argument(
            doc = "Typical mapping error rate.",
            fullName = "mapping-error-rate",
            minValue = 0.,
            optional = true
    )
    private double mappingErrorRate = 0.01;

    @Argument(
            doc = "Global contig-level unexplained variance scale.  If a single sample is provided, " +
                    "this input will be ignored.",
            fullName = "global-psi-scale",
            minValue = 0.,
            optional = true
    )
    private double globalPsiScale = 0.001;

    @Argument(
            doc = "Sample-specific contig-level unexplained variance scale.",
            fullName = "sample-psi-scale",
            minValue = 0.,
            optional = true
    )
    private double samplePsiScale = 0.0001;

    public List<String> generatePythonArguments(final DetermineGermlineContigPloidy.RunMode runMode) {
        final List<String> arguments = new ArrayList<>(Arrays.asList(
                String.format("--mapping_error_rate=%e", mappingErrorRate),
                String.format("--psi_s_scale=%e", samplePsiScale)));
        if (runMode == DetermineGermlineContigPloidy.RunMode.COHORT) {
            arguments.addAll(Arrays.asList(
                    String.format("--mean_bias_sd=%e", meanBiasStandardDeviation),
                    String.format("--psi_j_scale=%e", globalPsiScale)));
        }
        return arguments;
    }

    public void validate() {
        ParamUtils.isPositive(meanBiasStandardDeviation,
                "Contig-level mean bias standard deviation must be positive.");
        ParamUtils.isPositive(mappingErrorRate,
                "Typical mapping error rate must be positive.");
        ParamUtils.isPositive(globalPsiScale,
                "Global contig-level unexplained variance scale must be positive.");
        ParamUtils.isPositive(samplePsiScale,
                "Sample-specific contig-level unexplained variance scale must be positive.");
    }
}
