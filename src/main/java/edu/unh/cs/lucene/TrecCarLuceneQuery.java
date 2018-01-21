package edu.unh.cs.lucene;

import edu.unh.cs.TrecCarRepr;
import edu.unh.cs.treccar_v2.Data;
import edu.unh.cs.treccar_v2.read_data.DeserializeData;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;

/*
 * User: dietz
 * Date: 1/4/18
 * Time: 1:23 PM
 */

/**
 * Example of how to build a lucene index of trec car paragraphs
 */
public class TrecCarLuceneQuery {

    public static class MyQueryBuilder {
        TrecCarRepr trecCarParaRepr;
        String paragraphIndexName = "paragraph.lucene";

        private final Analyzer analyzer;
        private final List<String> searchFields;
        private final TrecCarRepr trecCarRepr;
        private List<String> tokens;
        private final String textSearchField;

        public MyQueryBuilder(Analyzer standardAnalyzer, List<String> searchFields, TrecCarRepr trecCarRepr){
            analyzer = standardAnalyzer;
            this.searchFields = searchFields;
            if(searchFields.size()>20) System.err.println("Warning: searching more than 20 fields, this may exceed the allowable number of 1024 boolean clauses.");
            textSearchField = trecCarRepr.getTextField().toString();
            this.trecCarRepr = trecCarRepr;
            tokens = new ArrayList<>(64);
        }

        public void addTokens(String content, Float weight, Map<String,Float> wordFreqs) throws IOException {
            TokenStream tokenStream = analyzer.tokenStream(textSearchField, new StringReader(content));
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                final String token = tokenStream.getAttribute(CharTermAttribute.class).toString();
                wordFreqs.compute(token, (t, oldV) ->
                                         (oldV==null)? weight : oldV + weight
                );
            }
            tokenStream.end();
            tokenStream.close();
        }

        public BooleanQuery toRm3Query(String queryStr, List<Map.Entry<String, Float>> relevanceModel) throws IOException {

            TokenStream tokenStream = analyzer.tokenStream(textSearchField, new StringReader(queryStr));
            tokenStream.reset();
            tokens.clear();
            while (tokenStream.incrementToken() && tokens.size()<64) {
                final String token = tokenStream.getAttribute(CharTermAttribute.class).toString();
                tokens.add(token);
            }
            tokenStream.end();
            tokenStream.close();
            BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();

            for (String searchField : this.searchFields) {
                for (String token : tokens) {
                    booleanQuery.add(new BoostQuery(new TermQuery(new Term(searchField, token)),1.0f), BooleanClause.Occur.SHOULD);
                }
            }

            // add RM3 terms
            for (String searchField : this.searchFields) {
                for (Map.Entry<String, Float> stringFloatEntry : relevanceModel.subList(0, Math.min(relevanceModel.size(), (64-tokens.size())))) {
                    String token = stringFloatEntry.getKey();
                    float weight = stringFloatEntry.getValue();
                    booleanQuery.add(new BoostQuery(new TermQuery(new Term(searchField, token)),weight), BooleanClause.Occur.SHOULD);
                }
            }


            return booleanQuery.build();
        }
        public BooleanQuery toQuery(String queryStr) throws IOException {

            TokenStream tokenStream = analyzer.tokenStream(textSearchField, new StringReader(queryStr));
            tokenStream.reset();
            tokens.clear();
            while (tokenStream.incrementToken() && tokens.size()<64) {
                final String token = tokenStream.getAttribute(CharTermAttribute.class).toString();
                tokens.add(token);
            }
            tokenStream.end();
            tokenStream.close();
            BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();

            for (String searchField : this.searchFields) {
                for (String token : tokens) {
                    booleanQuery.add(new TermQuery(new Term(searchField, token)), BooleanClause.Occur.SHOULD);
                }
            }


            return booleanQuery.build();
        }


    }

    private static void usage() {
        System.out.println("Command line parameters: (paragraph|page|entity|edgedoc) " +
                " (section|page) (run|display) OutlineCBOR INDEX RUNFile" +
                " (sectionPath|all|subtree|title|leafheading|interior)" +
                " (bm25|ql|default) (none|rm) [searchField1] [searchField2] ...\n" +
                "searchFields one of "+Arrays.toString(TrecCarRepr.TrecCarSearchField.values()));
        System.exit(-1);
    }
//        System.out.println("Command line parameters: (paragraphs|pages)CBOR LuceneINDEX");


    private static String queryModel;
    private static String retrievalModel;
    private static String expansionModel;
    private static String analyzer;

    public static void main(String[] args) throws IOException {
        System.setProperty("file.encoding", "UTF-8");

        if(args.length <1){
            usage();
        }
        if("--tool-version".equals(args[0])) {
            System.out.println("2");
            System.exit(0);
        }

        if (args.length < 9)
            usage();

        final String representation = args[0];
        TrecCarLuceneConfig.LuceneIndexConfig icfg = TrecCarLuceneConfig.getLuceneIndexConfig(representation);

        String queryAs = args[1];
        String output = args[2];
        TrecCarLuceneConfig.LuceneQueryConfig cfg = new TrecCarLuceneConfig.LuceneQueryConfig(icfg, !("display".equals(output)), "section".equals(queryAs));


        final String queryCborFile = args[3];
        final String indexPath = args[4];
        final String runFileName = args[5];

        queryModel = args[6];
        retrievalModel = args[7];
        expansionModel = args[8];
        analyzer = args[9];




        List<String> searchFields = null;
        if (args.length  > 10) searchFields = Arrays.asList(Arrays.copyOfRange(args, 10, args.length));

        System.out.println("Index loaded from "+indexPath+"/"+cfg.getIndexConfig().getIndexName());
        IndexSearcher searcher = setupIndexSearcher(indexPath, cfg.getIndexConfig().indexName);

        if ("bm25".equals(retrievalModel)) searcher.setSimilarity(new BM25Similarity());
        else if ("ql".equals(retrievalModel)) searcher.setSimilarity(new LMDirichletSimilarity(1500));
        // else default similarity

        List<String> searchFieldsUsed;
        if (searchFields == null) searchFieldsUsed = cfg.getIndexConfig().getSearchFields();
        else searchFieldsUsed = searchFields;


        final Analyzer queryAnalyzer = ("std".equals(analyzer))? new StandardAnalyzer():
                ("english".equals(analyzer)? new EnglishAnalyzer(): new StandardAnalyzer());


        final MyQueryBuilder queryBuilder = new MyQueryBuilder(queryAnalyzer, searchFieldsUsed, icfg.trecCarRepr );
        final QueryBuilder.QueryStringBuilder queryStringBuilder =
                ("sectionpath".equals(queryModel))? new QueryBuilder.SectionPathQueryStringBuilder() :
                        ("all".equals(queryModel) ? new QueryBuilder.OutlineQueryStringBuilder():
                            ("subtree".equals(queryModel) ? new QueryBuilder.SubtreeQueryStringBuilder():
                               ("title".equals(queryModel) ? new QueryBuilder.TitleQueryStringBuilder():
                                   ("leafheading".equals(queryModel) ? new QueryBuilder.LeafHeadingQueryStringBuilder():
                                       ("interior".equals(queryModel) ? new QueryBuilder.InteriorHeadingQueryStringBuilder():
                                         new QueryBuilder.SectionPathQueryStringBuilder()
                                   )))));



        final PrintWriter runfile = new PrintWriter(runFileName);

        if(cfg.queryAsSection ) {
            final FileInputStream fileInputStream3 = new FileInputStream(new File(queryCborFile));
            for (Data.Page page : DeserializeData.iterableAnnotations(fileInputStream3)) {
                System.out.println("\n\nPage: " + page.getPageId());
                for (List<Data.Section> sectionPath : page.flatSectionPaths()) {
                    System.out.println();
                    System.out.println(Data.sectionPathId(page.getPageId(), sectionPath) + "   \t " + Data.sectionPathHeadings(sectionPath));

                    final String queryStr = queryStringBuilder.buildSectionQueryStr(page, sectionPath);
                    final String queryId = Data.sectionPathId(page.getPageId(), sectionPath);

                    if ("rm".equals(expansionModel)){
                         oneExpandedQuery(searcher, queryBuilder, queryStr, queryId, cfg.isOutputAsRun(), runfile);
                    } else {
                        oneQuery(searcher, queryBuilder, queryStr, queryId, cfg.isOutputAsRun(), runfile);
                    }
                }
            }
            System.out.println();
        }
        else { //if(!cfg.queryAsSection){
            final FileInputStream fileInputStream3 = new FileInputStream(new File(queryCborFile));
            for (Data.Page page : DeserializeData.iterableAnnotations(fileInputStream3)) {
                    if (!cfg.outputAsRun)  System.out.println("\n\nPage: "+page.getPageId());

                    final String queryStr = queryStringBuilder.buildSectionQueryStr(page, Collections.emptyList());
                    final String queryId = page.getPageId();
                    if ("rm".equals(expansionModel)){
                        oneExpandedQuery(searcher, queryBuilder, queryStr, queryId, cfg.isOutputAsRun(), runfile);
                    } else {
                        oneQuery(searcher, queryBuilder, queryStr, queryId, cfg.isOutputAsRun(), runfile);
                    }
            }
            System.out.println();
        }

        runfile.close();

    }



    private static List<Map.Entry<String, Float>> relevanceModel(IndexSearcher searcher, MyQueryBuilder queryBuilder, String queryStr, int takeKDocs, int takeKTerms) throws IOException {
        final TrecCarRepr trecCarRepr = queryBuilder.trecCarRepr;
        final BooleanQuery booleanQuery = queryBuilder.toQuery(queryStr);
        TopDocs tops = searcher.search(booleanQuery, takeKDocs);
        ScoreDoc[] scoreDoc = tops.scoreDocs;

        final Map<String, Float> wordFreqs = new HashMap<>();
        queryBuilder.addTokens(queryStr, 1.0f, wordFreqs);

        // guess if we have log scores...
        boolean useLog = false;
        for (ScoreDoc score : scoreDoc) {
            if (score.score < 0.0) useLog = true;
            break;
        }

        // compute score normalizer
        double normalizer = 0.0;
        for (ScoreDoc score : scoreDoc) {
            if (useLog) normalizer += Math.exp(score.score);
            else normalizer += score.score;
        }
        if (useLog) normalizer = Math.log(normalizer);

        for (ScoreDoc score : scoreDoc) {
            Double weight = useLog ? (score.score - normalizer) : (score.score / normalizer);
            final Document doc = searcher.doc(score.doc); // to access stored content
            String fulltext = doc.getField(trecCarRepr.getTextField().name()).stringValue();

            queryBuilder.addTokens(fulltext, weight.floatValue(), wordFreqs);
        }

        ArrayList<Map.Entry<String, Float>> allWordFreqs = new ArrayList<>(wordFreqs.entrySet());
        allWordFreqs.sort(new Comparator<Map.Entry<String, Float>>() {
            @Override
            public int compare(Map.Entry<String, Float> kv1, Map.Entry<String, Float> kv2) {
                return Float.compare(kv2.getValue(), kv1.getValue()); // sort descending by flipping
            }
        });

        List<Map.Entry<String, Float>> expansionTerms = allWordFreqs.subList(0, Math.min(takeKTerms, allWordFreqs.size()));

        System.out.println("Expansions "+expansionTerms.toString());
        return expansionTerms;
    }

    private static void oneExpandedQuery(IndexSearcher searcher, MyQueryBuilder queryBuilder, String queryStr, String queryId, boolean outputAsRun, PrintWriter runfile) throws IOException {
        final TrecCarRepr trecCarRepr = queryBuilder.trecCarRepr;

        final List<Map.Entry<String, Float>> relevanceModel = relevanceModel(searcher, queryBuilder, queryStr, 20, 20);
        final BooleanQuery booleanQuery = queryBuilder.toRm3Query(queryStr, relevanceModel);

        TopDocs tops = searcher.search(booleanQuery, 100);
        ScoreDoc[] scoreDoc = tops.scoreDocs;
        System.out.println("Found "+scoreDoc.length+" RM3 results.");

        outputQueryResults(searcher, queryId, outputAsRun, runfile, trecCarRepr, scoreDoc);

    }

    private static void oneQuery(IndexSearcher searcher, MyQueryBuilder queryBuilder, String queryStr, String queryId, boolean outputAsRun, PrintWriter runfile) throws IOException {
        final TrecCarRepr trecCarRepr = queryBuilder.trecCarRepr;
        final BooleanQuery booleanQuery = queryBuilder.toQuery(queryStr);
        TopDocs tops = searcher.search(booleanQuery, 100);
        ScoreDoc[] scoreDoc = tops.scoreDocs;
        System.out.println("Found "+scoreDoc.length+" results.");

        outputQueryResults(searcher, queryId, outputAsRun, runfile, trecCarRepr, scoreDoc);
    }

    private static void outputQueryResults(IndexSearcher searcher, String queryId, boolean outputAsRun, PrintWriter runfile, TrecCarRepr trecCarRepr, ScoreDoc[] scoreDoc) throws IOException {
        for (int i = 0; i < scoreDoc.length; i++) {
            ScoreDoc score = scoreDoc[i];
            final Document doc = searcher.doc(score.doc); // to access stored content
            // print score and internal docid
            final String docId = doc.getField(trecCarRepr.getIdField().name()).stringValue();
            final float searchScore = score.score;
            final int searchRank = i+1;

            if(!outputAsRun) {
                System.out.println(docId + " (" + score.doc + "):  SCORE " + score.score);
                // access and print content
                System.out.println("  " + doc.getField(trecCarRepr.getTextField().name()).stringValue());
            }

            runfile.println(queryId + " Q0 " + docId + " " + searchRank + " " + searchScore + " Lucene-"+queryModel+"-"+retrievalModel);
        }
    }

    @NotNull
    private static IndexSearcher setupIndexSearcher(String indexPath, String typeIndex) throws IOException {
        Path path = FileSystems.getDefault().getPath(indexPath, typeIndex);
        Directory indexDir = FSDirectory.open(path);
        IndexReader reader = DirectoryReader.open(indexDir);
        return new IndexSearcher(reader);
    }


}