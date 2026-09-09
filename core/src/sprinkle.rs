//! Which words get an inline annotation, and how the reader's bar maps to that.
//!
//! A page annotated on every word is unreadable, so the inline layer is sprinkled: the bar
//! chooses a density and this decides, word by word, which occurrences are drawn. It is here
//! rather than on either platform because one setting has to mean one thing on both, and it
//! is decided without any state, so the same word in the same place is picked the same way on
//! every pass and the annotations never flicker between renders.

/// One word in every N: 1 is every word, 50 is a rare sprinkle.
pub const DENSITY_MIN: u32 = 1;
pub const DENSITY_MAX: u32 = 50;

/// How far the reader's bar is bent from linear toward geometric.
///
/// The bar controls a frequency, and frequency is felt in ratios rather than in N: the step
/// from one in two to one in four is a world apart, one in forty to one in forty-two is
/// nothing. A straight linear N spends most of the travel among sparse densities nobody can
/// tell apart. Bending it partway keeps the ends honest and gives the dense end the
/// resolution.
const LOG_MIX: f64 = 0.6;

/// A word's hash, deterministic across platforms and runs.
///
/// FNV-1a over the UTF-16 code units, because that is what both hosts index text with and a
/// hash that disagreed about a character outside the basic plane would pick different words
/// in a browser and on a phone.
pub fn hash(text: &str) -> u32 {
    let mut h: u32 = 2166136261;
    for unit in text.encode_utf16() {
        h ^= unit as u32;
        h = h.wrapping_mul(16777619);
    }
    h
}

/// The density at a position on the bar: 0 is sparse, one in fifty; 1 is dense, one in two.
pub fn density_for_pos(position: f64) -> u32 {
    let t = position.clamp(0.0, 1.0);
    let min = DENSITY_MIN as f64;
    let max = DENSITY_MAX as f64;
    let linear = max - t * (max - min);
    let geometric = max * (min / max).powf(t);
    let n = ((1.0 - LOG_MIX) * linear + LOG_MIX * geometric).round();
    (n as i64).clamp(DENSITY_MIN as i64, DENSITY_MAX as i64) as u32
}

/// Where on the bar a density sits, found by scanning the curve so the two stay exactly
/// consistent: an inverse derived by algebra would drift from the rounding above.
pub fn pos_for_density(density: u32, steps: u32) -> f64 {
    let mut best = 0.0;
    let mut best_error = u32::MAX;
    for i in 0..=steps {
        let position = i as f64 / steps as f64;
        let error = density_for_pos(position).abs_diff(density);
        if error < best_error {
            best_error = error;
            best = position;
        }
    }
    best
}

/// Whether this occurrence of a word is one of the annotated ones.
///
/// Two things shape the gate. Rarer words are the ones worth stopping on, so it favours them,
/// with length standing in for rarity: function words are short and the words a reader wants
/// to learn run longer, so a longer word shrinks N and is picked more often. And it keys on
/// the occurrence index, so repeats of one word are decided independently and land as a mix
/// down the page rather than all or nothing, since a word that is always annotated has
/// nothing left to teach after the first time.
///
/// The word is expected lowercased; the occurrence counts from zero within one pass.
pub fn picks(word: &str, occurrence: u32, density: u32) -> bool {
    // The end of the bar means every word and has to mean it exactly: the rarity boost would
    // otherwise hold short words back to one in two at the densest setting, and a reader who
    // asked for all of them would find some missing with no way to ask harder.
    if density <= DENSITY_MIN {
        return true;
    }
    let length = word.encode_utf16().count() as f64;
    let boost = (length / 5.0).clamp(0.6, 2.4);
    let n = ((density as f64 / boost).round() as u32).max(1);
    hash(&format!("{word}#{occurrence}")).is_multiple_of(n)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_ends_of_the_bar_are_what_they_say() {
        assert_eq!(density_for_pos(0.0), DENSITY_MAX);
        assert_eq!(density_for_pos(1.0), DENSITY_MIN);
    }

    #[test]
    fn the_bar_never_goes_backwards() {
        let mut last = density_for_pos(0.0);
        for step in 1..=100 {
            let now = density_for_pos(step as f64 / 100.0);
            assert!(
                now <= last,
                "density rose from {last} to {now} at step {step}"
            );
            last = now;
        }
    }

    #[test]
    fn the_densest_setting_takes_every_word() {
        for word in ["a", "the", "unfamiliar", "pronunciation"] {
            for occurrence in 0..5 {
                assert!(picks(word, occurrence, DENSITY_MIN));
            }
        }
    }

    #[test]
    fn one_word_is_decided_the_same_way_twice() {
        assert_eq!(picks("paragraph", 3, 12), picks("paragraph", 3, 12));
    }

    #[test]
    fn a_longer_word_is_taken_at_least_as_often() {
        let density = 20;
        let short: usize = (0..200).filter(|i| picks("the", *i, density)).count();
        let long: usize = (0..200)
            .filter(|i| picks("pronunciation", *i, density))
            .count();
        assert!(long > short, "long {long} short {short}");
    }

    #[test]
    fn the_inverse_lands_back_on_the_same_density() {
        for density in [1, 2, 5, 12, 30, 50] {
            let back = density_for_pos(pos_for_density(density, 100));
            assert!(back.abs_diff(density) <= 1, "{density} came back as {back}");
        }
    }
}
