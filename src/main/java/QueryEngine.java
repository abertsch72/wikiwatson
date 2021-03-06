import edu.stanford.nlp.simple.Sentence;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.store.SimpleFSDirectory;
import org.tartarus.snowball.ext.PorterStemmer;


import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class QueryEngine {
    boolean indexExists=true;
    boolean stem=true;
    boolean lemmatize = false;
    boolean changeSimilarity = false;
    String inputFilePath = "";
    QueryParser parser;
    Directory index;
    PorterStemmer stemmer;
    IndexSearcher search;
    String indexFolder = "index";
    String questions = "questions.txt";

    public QueryEngine(String inputFile){
        inputFilePath =inputFile;
        this.stemmer = new PorterStemmer();

        if(!indexExists) buildIndex();
        else {
            // load the index from disk
            try{
                Directory indexDirectory = FSDirectory.open(Paths.get(indexFolder));
                IndexReader reader = DirectoryReader.open(indexDirectory);
                search = new IndexSearcher(reader);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        StandardAnalyzer analyzer = new StandardAnalyzer();
        parser = new QueryParser("text", analyzer);
    }

    /**
     * Query the index, returning the title of the top page
     * This also prints the top page's name and score for debugging purposes
     * @param querystr the query string
     * @return
     */
    public String queryIndex(String querystr) {
       String ans = "";

       // apply stemming or lemmatization if flagged
        if(stem){
            String stemmed = "";
            for(String word: querystr.split("\\s+")){
                stemmer.setCurrent(word);
                stemmer.stem();
                stemmed += stemmer.getCurrent() + " ";
            }
            System.out.println("QUERY: " + stemmed);
            querystr = stemmed;
        } else if(lemmatize){
            Sentence query = new Sentence(querystr);
           querystr = String.join(" ", query.lemmas().toArray(new String[0]));
           System.out.println("QUERY: " + querystr);
        }

        try {
            Query q = parser.parse(QueryParser.escape(querystr));
            int hitsPerPage = 10;
            TopDocs docs = search.search(q, hitsPerPage);
            ScoreDoc[] hits = docs.scoreDocs;

            // change similarity from default, if flagged
            if(changeSimilarity) search.setSimilarity(new ClassicSimilarity());

            // if there was at least one result, check it out
            if(hits.length > 0){
                int docId = hits[0].doc;
                Document d = search.doc(docId);
                System.out.println(d.get("title") + "\t" +  hits[0].score);
                ans = d.get("title");
            }
        } catch (IOException | ParseException e){
            e.printStackTrace();
            System.err.println("Exception, exiting!");
            System.exit(1);
        }
        return ans;
    }

    /**
     * Constructs the index on disk
     * @param directory the directory to read data from
     * @param index the directory object to write the index to
     * @param config the configuration information for the index writer
     * @throws IOException
     */
    private void readInData(String directory, Directory index, IndexWriterConfig config) throws IOException{
        File f = new File(directory);
        File[] datafiles = f.listFiles();
        IndexWriter writer = new IndexWriter(index, config);
        if(changeSimilarity) config.setSimilarity(new ClassicSimilarity());
        for(File data: datafiles){
            try (Scanner inputScanner = new Scanner(data);
                 ) {
                String line;
                String currTitle = "";
                String fulltext = "";
                String stemmed = "";
                String[] tokens;
                while (inputScanner.hasNextLine()) {
                    line = inputScanner.nextLine();
                    if(line.startsWith("[[")){ // start of new article, index the previous one
                        if(fulltext != ""){

                            // lemmatize if necessary
                            if(lemmatize){
                                edu.stanford.nlp.simple.Document doc = new edu.stanford.nlp.simple.Document(fulltext);
                                String lemmas = "";
                                for(Sentence sent: doc.sentences()){
                                    lemmas += String.join(" ", sent.lemmas().toArray(new String[0]));
                                }
                                fulltext = lemmas;
                            }
                            System.out.println(fulltext);
                            addDoc(writer, currTitle, fulltext);
                        }
                        fulltext = "";
                        currTitle = line.trim();
                        currTitle = currTitle.substring(2, currTitle.length() - 2); //remove [[ ]] from title
                        System.out.println(currTitle);
                    } else{
                        // stem if necessary, add to current document
                        if(stem) {
                            stemmed = "";
                            stemmer.setCurrent(line);
                            stemmer.stem();
                            for (String word : line.split("\\s+")) {
                                stemmer.setCurrent(word);
                                stemmer.stem();
                                stemmed += stemmer.getCurrent() + " ";
                            }
                            fulltext = fulltext + stemmed + "\n";
                        } else fulltext = fulltext + line + "\n";
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        writer.close();
    }

    /**
     * Constructs an index on disk based on the input files, calling readInData to do the bulk of the file parsing
     * Follows the Lucene in 5 minutes tutorial.
     */
    private void buildIndex() {
        //Get file from resources folder
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource(inputFilePath).getFile());
        StandardAnalyzer analyzer = new StandardAnalyzer();
        try{
            index = new SimpleFSDirectory(Paths.get("/home/abertsch/projects/wikiwatson/index"));

            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            parser = new QueryParser("text", analyzer);
            readInData("/home/abertsch/projects/wikiwatson/data", index, config);
            IndexReader reader = DirectoryReader.open(index);
            search = new IndexSearcher(reader);

            // update similarity, if flagged
            if(changeSimilarity) search.setSimilarity(new ClassicSimilarity());
        } catch (IOException e) {
            e.printStackTrace();
        }

        indexExists = true;
    }

    /**
     * Query the index for all 100 Jeopardy questions and return the number of correct answers.
     * @return the number of correct answers
     */
    public int queryAll() {
        System.out.println("in queryAll");
        int correct = 0;
        int numFound = 0; // number of questions processed
        String category = null, query = null;
        String answer = null;

        try (Scanner readIn = new Scanner(new File(this.questions))) {
            while (readIn.hasNextLine() && numFound != 100) {
                // read in a question part
                if (category == null) {
                    category = readIn.nextLine();
                } else if (query == null) {
                    query = readIn.nextLine();
                } else if (answer == null) {
                    System.out.println(query);
                    answer = readIn.nextLine();
                } else {
                    readIn.nextLine(); // skip empty line
                    String claim = queryIndex(query + " " + category);
                    for(String option: answer.split("[|]")){
                        if(claim.equals(option)) {
                            System.out.println("MATCH!");
                            correct++;
                        } else {
                            System.out.println("'" + claim + "' != '" + option + "'");
                        }
                    }
                    System.out.println("\n");

                    // reset to prep for next question
                    category = null;
                    query = null;
                    answer = null;
                    numFound++;
                }
            }

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return correct;
    }
    /**
     * Helper method to add a document to the index
     * @param writer the object that writes to the index
     * @param docName to put in the "docid" field
     * @param text to put in the "text" field
     * @throws IOException
     */
    private static void addDoc(IndexWriter writer, String docName, String text) throws IOException {
        Document doc = new Document();
        doc.add(new TextField("text", text, Field.Store.YES));
        doc.add(new StringField("title", docName, Field.Store.YES));
        writer.addDocument(doc);
    }

    /**
     * Entrypoint; constructs index (if not already built) and runs the queries for all documents
     * @param args
     */
    public static void main(String[] args ) {
        QueryEngine q = new QueryEngine("");
        System.out.println("NUMBER CORRECT: " + q.queryAll() + "/100");
    }

}