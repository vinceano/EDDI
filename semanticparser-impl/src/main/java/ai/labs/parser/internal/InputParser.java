package ai.labs.parser.internal;

import ai.labs.parser.IInputParser;
import ai.labs.parser.extensions.corrections.ICorrection;
import ai.labs.parser.extensions.dictionaries.IDictionary;
import ai.labs.parser.extensions.normalizers.INormalizer;
import ai.labs.parser.internal.matches.MatchingResult;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.internal.matches.Suggestion;
import ai.labs.parser.model.FoundPhrase;
import ai.labs.parser.model.FoundUnknown;
import ai.labs.parser.model.Unknown;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
public class InputParser implements IInputParser {
    private static final Pattern REGEX_MATCHER_MULTIPLE_SPACES = Pattern.compile(" +");
    private static final String BLANK_CHAR = " ";

    private List<INormalizer> normalizers;
    private List<IDictionary> dictionaries;
    private List<ICorrection> corrections;
    private Map<IDictionary.IWord, List<IDictionary.IPhrase>> phrasesMap;

    public InputParser(List<IDictionary> dictionaries) {
        this(dictionaries, Collections.emptyList());
    }

    public InputParser(List<IDictionary> dictionaries, List<ICorrection> corrections) {
        this(Collections.emptyList(), dictionaries, corrections);
    }

    public InputParser(List<INormalizer> normalizers, List<IDictionary> dictionaries, List<ICorrection> corrections) {
        this.normalizers = normalizers;
        this.dictionaries = dictionaries;
        this.corrections = corrections;
        phrasesMap = preparePhrases(dictionaries);
    }

    @Override
    public List<RawSolution> parse(String sentence) throws InterruptedException {
        return parse(sentence, Collections.emptyList());
    }

    @Override
    public String normalize(final String sentence) throws InterruptedException {
        String normalizedSentence = iterateNormalizers(sentence);
        normalizedSentence = normalizeWhitespaces(normalizedSentence);
        return normalizedSentence;
    }

    private String normalizeWhitespaces(String normalizedSentence) {
        return REGEX_MATCHER_MULTIPLE_SPACES.matcher(normalizedSentence.trim()).replaceAll(BLANK_CHAR);
    }

    @Override
    public List<RawSolution> parse(final String sentence, final List<IDictionary> temporaryDictionaries)
            throws InterruptedException {

        InputHolder holder = new InputHolder();
        holder.input = sentence.split(" ");

        for (; holder.index < holder.input.length; holder.index++) {
            final String currentInputPart = holder.input[holder.index];

            iterateDictionaries(holder, currentInputPart, temporaryDictionaries);
            iterateDictionaries(holder, currentInputPart, dictionaries);

            iterateCorrections(holder, currentInputPart);

            if (holder.getMatchingResultSize(holder.index) == 0) {
                FoundUnknown foundUnknown = new FoundUnknown(new Unknown(currentInputPart));
                addDictionaryEntriesTo(holder, currentInputPart, Collections.singletonList(foundUnknown));
            }
        }

        return lookupPhrases(holder, preparePhrases(temporaryDictionaries));
    }

    private String iterateNormalizers(String sentence) throws InterruptedException {
        for (INormalizer normalizer : normalizers) {
            throwExceptionIfInterrupted("normalizers");
            sentence = normalizer.normalize(sentence);
        }

        return sentence;
    }

    private void iterateDictionaries(InputHolder holder, String currentInputPart, List<IDictionary> dictionaries)
            throws InterruptedException {
        for (IDictionary dictionary : dictionaries) {
            throwExceptionIfInterrupted("dictionaries");

            //lookup input part in dictionary
            List<IDictionary.IFoundWord> dictionaryEntries = dictionary.lookupTerm(currentInputPart);
            if (dictionaryEntries.size() > 0) {
                //add dictionary entries to final result list
                addDictionaryEntriesTo(holder, currentInputPart, dictionaryEntries);
            }
        }
    }

    private void iterateCorrections(InputHolder holder, String currentInputPart) throws InterruptedException {
        for (ICorrection correction : corrections) {
            throwExceptionIfInterrupted("corrections");
            if (!correction.lookupIfKnown() && holder.getMatchingResultSize(holder.index) != 0) {
                //skipped corrections because input part is already known.
                continue;
            }

            List<IDictionary.IFoundWord> correctedWords = correction.correctWord(currentInputPart);
            if (correctedWords.size() > 0) {
                addDictionaryEntriesTo(holder, currentInputPart, correctedWords);
            }
        }

        if (holder.getMatchingResultSize(holder.index) == 0) {
            FoundUnknown foundUnknown = new FoundUnknown(new Unknown(currentInputPart));
            addDictionaryEntriesTo(holder, currentInputPart, Collections.singletonList(foundUnknown));
        }
    }

    private void throwExceptionIfInterrupted(String currentOperation) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            String message = String.format("Parser was interrupted while processing %s.", currentOperation);
            throw new InterruptedException(message);
        }
    }

    private void addDictionaryEntriesTo(InputHolder holder, String matchedInputValue,
                                        List<IDictionary.IFoundWord> foundWords) {
        for (IDictionary.IFoundWord foundWord : foundWords) {
            MatchingResult matchingResult = new MatchingResult();
            matchingResult.addResult(foundWord);
            holder.addMatch(holder.index, matchedInputValue, matchingResult);
        }
    }

    private List<RawSolution> lookupPhrases(InputHolder holder,
                                            Map<IDictionary.IWord, List<IDictionary.IPhrase>> tmpPhrasesMap)
            throws InterruptedException {

        List<RawSolution> possibleSolutions = new LinkedList<>();
        Iterator<Suggestion> suggestionIterator = holder.createSolutionIterator();

        int maxIterations = 2;
        int currentIteration = 0;
        while (suggestionIterator.hasNext()) {
            throwExceptionIfInterrupted("phrases");
            currentIteration++;
            Suggestion suggestion = suggestionIterator.next();
            List<IDictionary.IFoundWord> foundWords = suggestion.build();
            List<IDictionary.IPhrase> phrasesContainingFoundWords =
                    getPhrasesContainingFoundWords(foundWords, Arrays.asList(phrasesMap, tmpPhrasesMap));

            RawSolution rawSolution = null;
            boolean matchingCompleted = false;

            //first try: look for full matches (one/more phrases)
            for (IDictionary.IPhrase phrase : phrasesContainingFoundWords) {
                if (isInterrupted()) {
                    break;
                }

                if (phrase.getWords().size() <= foundWords.size()) {
                    foundWords = lookForMatch(foundWords, phrase);
                    if (foundWords.contains(createPhrase(phrase, 1.0))) {
                        rawSolution = new RawSolution(RawSolution.Match.FULLY);
                    }

                    if (!anyWordsLeft(foundWords)) {
                        matchingCompleted = true;
                        break;
                    }
                }
            }

            //if we could match ALL the foundWords to phrase(s) we return
            if (rawSolution != null && matchingCompleted) {
                rawSolution.setDictionaryEntries(foundWords);
                possibleSolutions.add(rawSolution);
                return possibleSolutions;
            }

            //second try: look for incomplete matches
            for (IDictionary.IPhrase phrase : phrasesContainingFoundWords) {
                if (isInterrupted()) {
                    break;
                }

                if (phrase.getWords().size() > foundWords.size()) {
                    foundWords = lookForPartlyMatch(foundWords, phrase);
                    if (foundWords.contains(createPhrase(phrase, 0.5))) {
                        if (rawSolution == null) {
                            rawSolution = new RawSolution(RawSolution.Match.PARTLY);
                        }
                    }

                    if (!anyWordsLeft(foundWords)) {
                        matchingCompleted = true;
                        break;
                    }
                }
            }

            if (rawSolution != null) {
                rawSolution.setDictionaryEntries(foundWords);
                if (rawSolution.getMatch() == RawSolution.Match.FULLY) {
                    possibleSolutions.add(0, rawSolution);
                } else {
                    possibleSolutions.add(rawSolution);
                }

                if (matchingCompleted) {
                    return possibleSolutions;
                }
            } else if (!foundWords.isEmpty()) {
                //if we are here, we know it is not a phrase in this iteration, neither fully nor partly
                if (foundWords.stream().anyMatch(word -> word.getFoundWord().isPartOfPhrase())) {
                    rawSolution = new RawSolution(RawSolution.Match.NOTHING);
                    rawSolution.setDictionaryEntries(foundWords);
                    addIfAbsent(possibleSolutions, rawSolution);
                } else {
                    //found no words from phrase, but actual dictionary word(s), so we treat in more prominent
                    rawSolution = new RawSolution(RawSolution.Match.PARTLY);
                    rawSolution.setDictionaryEntries(foundWords);
                    if (possibleSolutions.isEmpty()) {
                        possibleSolutions.add(rawSolution);
                    } else {
                        int maxIndex = possibleSolutions.size() - 1;
                        for (int i = maxIndex; i >= 0; i--) {
                            RawSolution tmpSolution = possibleSolutions.get(i);
                            if (tmpSolution.getMatch().equals(RawSolution.Match.NOTHING)) {
                                if (i == 0) {
                                    addIfAbsent(possibleSolutions, rawSolution, 0);
                                }
                            } else {
                                addIfAbsent(possibleSolutions, rawSolution, i + 1);
                            }
                        }
                    }
                }
            }

            if (currentIteration > maxIterations) {
                break;
            }
        }

        return possibleSolutions;
    }

    private void addIfAbsent(List<RawSolution> possibleSolutions, RawSolution rawSolution) {
        addIfAbsent(possibleSolutions, rawSolution, -1);
    }

    private void addIfAbsent(List<RawSolution> possibleSolutions, RawSolution rawSolution, int insertAtIndex) {
        if (possibleSolutions.stream().noneMatch(rawSolution::equals)) {
            if (insertAtIndex > -1 && insertAtIndex < possibleSolutions.size()) {
                possibleSolutions.add(insertAtIndex, rawSolution);
            } else {
                possibleSolutions.add(rawSolution);
            }
        }
    }

    private boolean isInterrupted() {
        return Thread.currentThread().isInterrupted();
    }

    private boolean anyWordsLeft(List<IDictionary.IFoundWord> foundWords) {
        return foundWords.stream().anyMatch(foundWord -> foundWord.getFoundWord().isPartOfPhrase());
    }

    /**
     * @param foundWords all inputEntries
     * @param phrase     to be checked for a match with foundWords
     * @return the list of IDictionaryEntry which does NOT FULLY match the phrase, will be returned for further lookup.
     * In case a phrase has been found, it will be substituted with the range of matching foundWords
     */
    private List<IDictionary.IFoundWord> lookForMatch(List<IDictionary.IFoundWord> foundWords,
                                                      IDictionary.IPhrase phrase) {
        List<IDictionary.IWord> words = convert(foundWords);
        int startOfMatch = Collections.indexOfSubList(words, phrase.getWords());
        if (startOfMatch > -1) {
            //does match
            List<IDictionary.IFoundWord> ret = new LinkedList<>();
            if (startOfMatch > 0) {
                ret.addAll(foundWords.subList(0, startOfMatch));
            }
            ret.add(createPhrase(phrase, 1.0));
            int rangeOfMatch = startOfMatch + phrase.getWords().size();
            if (rangeOfMatch < foundWords.size()) {
                ret.addAll(foundWords.subList(rangeOfMatch, foundWords.size()));
            }

            return ret;
        } else {
            // does not match
            return foundWords;
        }
    }

    private List<IDictionary.IWord> convert(List<IDictionary.IFoundWord> foundWords) {
        return foundWords.stream().
                map(IDictionary.IFoundWord::getFoundWord).collect(Collectors.toList());
    }

    private IDictionary.IFoundWord createPhrase(IDictionary.IPhrase phrase, double matchingAccuracy) {
        return new FoundPhrase(phrase, false, matchingAccuracy);
    }

    private List<IDictionary.IFoundWord> lookForPartlyMatch(List<IDictionary.IFoundWord> dictionaryEntries,
                                                            IDictionary.IPhrase phrase) {
        List<IDictionary.IWord> phraseWords = phrase.getWords();
        int startOfMatch = Collections.indexOfSubList(phraseWords, dictionaryEntries);
        if (startOfMatch > -1) {
            //does match
            List<IDictionary.IFoundWord> ret = new LinkedList<>();
            if (startOfMatch > 0) {
                ret.addAll(dictionaryEntries.subList(0, startOfMatch - 1));
            }
            ret.add(createPhrase(phrase, 1.0));
            int rangeOfMatch = startOfMatch + phraseWords.size();
            if (rangeOfMatch < dictionaryEntries.size()) {
                ret.addAll(dictionaryEntries.subList(rangeOfMatch, dictionaryEntries.size()));
            }

            return ret;
        } else {
            // does not match
            return dictionaryEntries;
        }
    }

    private Map<IDictionary.IWord, List<IDictionary.IPhrase>> preparePhrases(List<IDictionary> dictionaries) {
        Map<IDictionary.IWord, List<IDictionary.IPhrase>> phrasesMap = new HashMap<>();
        dictionaries.stream().map(IDictionary::getPhrases).flatMap(Collection::stream).
                forEach(phrase -> phrase.getWords().stream().
                        map(wordOfPhrase -> phrasesMap.computeIfAbsent(wordOfPhrase, k -> new LinkedList<>())).
                        forEach(phrases -> phrases.add(phrase)));

        phrasesMap.keySet().stream().map(phrasesMap::get).
                filter(phrases -> phrases.size() > 1).forEach(this::orderPhrasesByLength);

        return phrasesMap;
    }

    private void orderPhrasesByLength(List<IDictionary.IPhrase> phrases) {
        phrases.sort(Collections.reverseOrder((o1, o2) -> {
            int lengthWord1 = o1.getWords().size();
            int lengthWord2 = o2.getWords().size();
            if (lengthWord1 != lengthWord2) {
                return Integer.compare(lengthWord1, lengthWord2);
            } else {
                return o1.getValue().compareTo(o2.getValue());
            }
        }));
    }

    private List<IDictionary.IPhrase> getPhrasesContainingFoundWords(
            List<IDictionary.IFoundWord> foundWords,
            List<Map<IDictionary.IWord, List<IDictionary.IPhrase>>> phrasesMaps) {

        List<IDictionary.IPhrase> ret = new LinkedList<>();
        foundWords.stream().filter(foundWord -> !foundWord.isPhrase()).
                forEach(foundWord -> phrasesMaps.stream().
                        map(phrasesMap -> phrasesMap.get(foundWord.getFoundWord())).
                        filter(Objects::nonNull).flatMap(Collection::stream).
                        filter(phrase -> !ret.contains(phrase)).forEach(ret::add));

        return ret;
    }
}
