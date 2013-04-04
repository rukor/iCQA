package com.icqa.syllabus;


import com.amazonaws.mturk.addon.*;
import com.amazonaws.mturk.requester.HIT;
import com.amazonaws.mturk.service.axis.RequesterService;
import com.amazonaws.mturk.util.PropertiesClientConfig;
import net.sf.json.JSONArray;
import org.apache.commons.lang.StringUtils;
import org.apache.lucene.queryParser.ParseException;

import java.io.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;


public class Mturk {

    public static final int POSTS_PER_TOPIC = 2;
    public static final int TOP_N = 5;
    public static final int HITS_COUNT = 25;
    public static final int POSTS_PER_HIT = 52;

    public static int СURRENT_ID = 0;
    // Defining the locations of the input files
    private RequesterService service;
    private static String inputFile = "res/mturk.input";
    private static String propertiesFile = "res/mturkHIT.properties";
    private static String questionFile = "res/mturk.question";

    public Mturk() {
        service = new RequesterService(new PropertiesClientConfig("res/mturk.properties"));
    }

    /**
     * Check to see if there are sufficient funds.
     * @return true if there are sufficient funds.  False otherwise.
     */
    public boolean hasEnoughFund() {
        double balance = service.getAccountBalance();
        System.out.println("Got account balance: " + RequesterService.formatCurrency(balance));
        return balance > 0;
    }

    /**
     * Create the website category HITs.
     *
     */
    public void createSiteCategoryHITs() {
        try {

            //Loading the input file.  The input file is a tab delimited file where the first row
            //defines the fields/variables and the remaining rows contain the values for each HIT.
            //Each row represents a unique HIT.  The SDK uses the Apache Velocity engine to merge
            //the input values into a templatized QAP file.
            //Please refer to http://velocity.apache.org for more details on this engine and
            //how to specify variable names.  Apache Velocity is fully supported so there may be
            //additional functionality you can take advantage of if you know the Velocity syntax.
            HITDataInput input = new HITDataCSVReader(inputFile);

            //Loading the question (QAP) file.  This QAP file contains Apache Velocity variable names where the values
            //from the input file are to be inserted.  Essentially the QAP becomes a template for the input file.
            HITQuestion question = new HITQuestion(questionFile);

            //Loading the HIT properties file.  The properties file defines two system qualifications that will
            //be used for the HIT.  The properties file can also be a Velocity template.  Currently, only
            //the "annotation" field is allowed to be a Velocity template variable.  This allows the developer
            //to "tie in" the input value to the results.
            HITProperties props = new HITProperties(propertiesFile);

            HIT[] hits = null;

            // Create multiple HITs using the input, properties, and question files

            System.out.println("--[Loading HITs]----------");
            Date startTime = new Date();
            System.out.println("  Start time: " + startTime);

            //The simpliest way to bulk load a large number of HITs where all details are defined in files.
            //When using this method, it will automatically create output files of the following type:
            //  - <your input file name>.success - A file containing the HIT IDs and HIT Type IDs of
            //                                     all HITs that were successfully loaded.  This file will
            //                                     not exist if there are no HITs successfully loaded.
            //  - <your input file name>.failure - A file containing the input rows that failed to load.
            //                                     This file will not exist if there are no failures.
            //The .success file can be used in subsequent operations to retrieve the results that workers submitted.
            HITDataOutput success = new HITDataCSVWriter(inputFile + ".success");
            HITDataOutput failure = new HITDataCSVWriter(inputFile + ".failure");
            hits = service.createHITs(input, props, question, success, failure);

            System.out.println("--[End Loading HITs]----------");
            Date endTime = new Date();
            System.out.println("  End time: " + endTime);
            System.out.println("--[Done Loading HITs]----------");
            System.out.println("  Total load time: " + (endTime.getTime() - startTime.getTime())/1000 + " seconds.");

            if (hits == null) {
                throw new Exception("Could not create HITs");
            }

        } catch (Exception e) {
            System.err.println(e.getLocalizedMessage());
        }
    }


    public ArrayList<Topic> getTopics(ArrayList<Topic> allTopics){
        ArrayList<Topic> result = new ArrayList<Topic>();
        for(Topic topic : allTopics){
            if(!topic.hasChildren()){
                result.add(topic);
            }else {
                result.addAll(getTopics(topic.mChildren));
            }
        }
        return result;
    }

    public String pickPosts(ArrayList<Topic> topics, int pos){

        ArrayList<String> posts = new ArrayList<String>();

        for(Topic item : topics){
            for(int i=0; i < POSTS_PER_TOPIC; ++i){
                if(i + pos * POSTS_PER_TOPIC < item.mPosts.size())
                    posts.add(item.mPosts.get(i + pos * POSTS_PER_TOPIC).toJSON());
            }
        }

        return StringUtils.join(posts, ",");
    }

    public static String readFile(String name) throws IOException {
        StringBuilder contents = new StringBuilder();
        BufferedReader reader = new BufferedReader(new FileReader(name));
        String line1;
        while((line1 = reader.readLine()) != null){
            contents.append(line1);
            contents.append("\n");
        }
        return contents.toString();
    }

    public static Topic.Post getPost(long postId, Connection connection) throws SQLException {
        PreparedStatement selectStatement = connection.prepareStatement("SELECT * FROM forum_node WHERE id=?");
        PreparedStatement answersStatement = connection.prepareStatement(
                "SELECT * FROM forum_node WHERE parent_id=? ORDER BY score DESC");

        selectStatement.setLong(1, postId);
        selectStatement.execute();
        ResultSet resultSet = selectStatement.getResultSet();
        if(resultSet.next()){
            ArrayList<Topic.Post> answers = new ArrayList<Topic.Post>();
            answersStatement.setLong(1, postId);
            answersStatement.execute();
            ResultSet answersResultSet = answersStatement.getResultSet();
            while(answersResultSet.next()){
                answers.add(new Topic.Post(answersResultSet.getLong("id"), answersResultSet.getString("title"),
                        answersResultSet.getString("body"), answersResultSet.getInt("score"),
                        answersResultSet.getString("state_string").contains("accepted"), null));
            }
            return new Topic.Post(postId, resultSet.getString("title"), resultSet.getString("body"),
                resultSet.getInt("score"), resultSet.getBoolean("marked"), answers);
        }
        return null;
    }

    public ArrayList<Topic> loadSyllabus(String json){
        JSONArray jsonArray = JSONArray.fromObject(json);

        ArrayList<Topic> res = new ArrayList<Topic>(jsonArray.size());
        for(int i=0; i<jsonArray.size(); ++i){
            res.add(new Topic(jsonArray.getJSONObject(i), null));
        }

        return res;
    }

    public void createInputFile(){
        try{
            String template = readFile("res/hit.html");

            Connection connection = Main.getConnection();

            BM25FSearch bm25FSearch = new BM25FSearch(Main.BM_25_F_PARAMETERS);

            BufferedWriter inputFileWriter = new BufferedWriter(new FileWriter(inputFile));
            inputFileWriter.write("id\n");


            String syllabusJSON = readFile("res/syllabus.json");
            ArrayList<Topic> syllabus = loadSyllabus(syllabusJSON);
            ArrayList<Topic> topics = getTopics(syllabus);

            handleWeight(new float[]{0.6f, 0.3f, 0.1f}, template, connection, bm25FSearch, inputFileWriter, syllabusJSON, topics);
            handleWeight(new float[]{1.0f, 0.0f, 0.0f}, template, connection, bm25FSearch, inputFileWriter, syllabusJSON, topics);
            inputFileWriter.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void handleWeight(float[] boosts, String template, Connection connection, BM25FSearch bm25FSearch, BufferedWriter inputFileWriter, String syllabusJSON, ArrayList<Topic> topics) throws IOException, ParseException, SQLException {
        bm25FSearch.setBoosts(boosts);

        ArrayList<Topic.Post> heap = new ArrayList<Topic.Post>(TOP_N * topics.size());

        Set<Long> usedPosts = new HashSet<Long>();

        for(Topic topic : topics){
            long[] docIds = bm25FSearch.getTopN(topic.getQuery(), TOP_N + 40);

            int addedCount = 0;
            for(long id : docIds){
                if(usedPosts.contains(id)) continue;
                Topic.Post post = getPost(id, connection);
                if(post != null){
                    usedPosts.add(id);
                    heap.add(post);
                    addedCount += 1;
                }
                if(addedCount == TOP_N)
                    break;
            }
        }

        int hitsCount = heap.size() / POSTS_PER_HIT + (heap.size() % POSTS_PER_HIT != 0 ? 1 : 0);


        for(int i=0; i < hitsCount; ++i){
            inputFileWriter.write((СURRENT_ID + i) + "\n");

            List posts = heap.subList(i * POSTS_PER_HIT, Math.min((i + 1) * POSTS_PER_HIT, heap.size()));

            StringBuilder postsStringBuilder = new StringBuilder();
            for(int j=0; j<posts.size(); ++j){
                Topic.Post post = (Topic.Post)posts.get(j);
                postsStringBuilder.append(post.toJSON());
                if(j != posts.size() - 1)
                    postsStringBuilder.append(",");
            }

            System.out.println(posts.size() + " questions added to HIT #" + (СURRENT_ID + i));

            String postsString = postsStringBuilder.toString();
            String html = template.replace("$syllabus$", syllabusJSON).replace("$posts$", postsString)
                    .replace("$weightScheme$", bm25FSearch.getWeightScheme());
            BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter("hits/hit" + (СURRENT_ID + i) + ".html"));
            bufferedWriter.write(html);
            bufferedWriter.close();
        }
        СURRENT_ID += hitsCount;
    }

    public static void main(String[] args) {

        Mturk app = new Mturk();

        //app.createInputFile();
        app.createSiteCategoryHITs();
    }
}
