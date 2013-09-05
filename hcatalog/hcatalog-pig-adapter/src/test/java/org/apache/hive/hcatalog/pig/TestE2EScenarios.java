/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hcatalog.pig;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;

import junit.framework.TestCase;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hive.cli.CliSessionState;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hcatalog.HcatTestUtils;
import org.apache.hcatalog.common.HCatConstants;
import org.apache.hcatalog.common.HCatContext;
import org.apache.hcatalog.data.HCatRecord;
import org.apache.hcatalog.data.schema.HCatSchema;
import org.apache.hcatalog.mapreduce.HCatInputFormat;
import org.apache.hcatalog.mapreduce.HCatOutputFormat;
import org.apache.hcatalog.mapreduce.OutputJobInfo;
import org.apache.hcatalog.mapreduce.HCatMapRedUtil;
import org.apache.pig.ExecType;
import org.apache.pig.PigServer;
import org.apache.pig.data.Tuple;

public class TestE2EScenarios extends TestCase {

    private static final String TEST_DATA_DIR = System.getProperty("user.dir") +
        "/build/test/data/" + TestHCatLoader.class.getCanonicalName();
    private static final String TEST_WAREHOUSE_DIR = TEST_DATA_DIR + "/warehouse";

    private static final String TEXTFILE_LOCN = TEST_DATA_DIR + "/textfile";

    private static Driver driver;

    protected String storageFormat() {
        return "orc";
    }

    @Override
    protected void setUp() throws Exception {

        File f = new File(TEST_WAREHOUSE_DIR);
        if (f.exists()) {
            FileUtil.fullyDelete(f);
        }
        new File(TEST_WAREHOUSE_DIR).mkdirs();

        HiveConf hiveConf = new HiveConf(this.getClass());
        hiveConf.set(HiveConf.ConfVars.PREEXECHOOKS.varname, "");
        hiveConf.set(HiveConf.ConfVars.POSTEXECHOOKS.varname, "");
        hiveConf.set(HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY.varname, "false");
        hiveConf.set(HiveConf.ConfVars.METASTOREWAREHOUSE.varname, TEST_WAREHOUSE_DIR);
        driver = new Driver(hiveConf);
        SessionState.start(new CliSessionState(hiveConf));

    }

    @Override
    protected void tearDown() throws Exception {
        dropTable("inpy");
        dropTable("rc5318");
        dropTable("orc5318");
    }

    private void dropTable(String tablename) throws IOException, CommandNeedRetryException {
        driver.run("drop table " + tablename);
    }

    private void createTable(String tablename, String schema, String partitionedBy, String storageFormat) throws IOException, CommandNeedRetryException {
        String createTable;
        createTable = "create table " + tablename + "(" + schema + ") ";
        if ((partitionedBy != null) && (!partitionedBy.trim().isEmpty())) {
            createTable = createTable + "partitioned by (" + partitionedBy + ") ";
        }
        if (storageFormat != null){
            createTable = createTable + "stored as " +storageFormat;
        }
        driverRun(createTable);
    }

    private void driverRun(String cmd) throws IOException, CommandNeedRetryException {
        int retCode = driver.run(cmd).getResponseCode();
        if (retCode != 0) {
            throw new IOException("Failed to run ["
                + cmd + "], return code from hive driver : [" + retCode + "]");
        }
    }

    private void pigDump(String tableName) throws IOException {
        PigServer server = new PigServer(ExecType.LOCAL);

        System.err.println("===");
        System.err.println(tableName+":");
        server.registerQuery("X = load '" + tableName
            + "' using org.apache.hcatalog.pig.HCatLoader();");
        Iterator<Tuple> XIter = server.openIterator("X");
        while (XIter.hasNext()) {
            Tuple t = XIter.next();
            for (Object o : t.getAll()){
                System.err.print(
                    "\t(" + o.getClass().getName() + ":"
                    + o.toString() + ")"
                );
            }
            System.err.println("");
        }
        System.err.println("===");
    }


    private void copyTable(String in, String out) throws IOException, InterruptedException {
        Job ijob = new Job();
        Job ojob = new Job();
        HCatInputFormat inpy = new HCatInputFormat();
        inpy.setInput(ijob , null, in);
        HCatOutputFormat oupy = new HCatOutputFormat();
        oupy.setOutput(ojob,
            OutputJobInfo.create(null, out, new HashMap<String,String>()
            ));

        // Test HCatContext

        System.err.println("HCatContext INSTANCE is present : " +HCatContext.INSTANCE.getConf().isPresent());
        if (HCatContext.INSTANCE.getConf().isPresent()){
            System.err.println("HCatContext tinyint->int promotion says " +
                HCatContext.INSTANCE.getConf().get().getBoolean(
                    HCatConstants.HCAT_DATA_TINY_SMALL_INT_PROMOTION,
                    HCatConstants.HCAT_DATA_TINY_SMALL_INT_PROMOTION_DEFAULT));
        }

        HCatSchema tableSchema = inpy.getTableSchema(ijob.getConfiguration());
        System.err.println("Copying from ["+in+"] to ["+out+"] with schema : "+ tableSchema.toString());
        oupy.setSchema(ojob, tableSchema);
        oupy.checkOutputSpecs(ojob);
        OutputCommitter oc = oupy.getOutputCommitter(createTaskAttemptContext(ojob.getConfiguration()));
        oc.setupJob(ojob);

        for (InputSplit split : inpy.getSplits(ijob)){

            TaskAttemptContext rtaskContext = createTaskAttemptContext(ijob.getConfiguration());
            TaskAttemptContext wtaskContext = createTaskAttemptContext(ojob.getConfiguration());

            RecordReader<WritableComparable, HCatRecord> rr = inpy.createRecordReader(split, rtaskContext);
            rr.initialize(split, rtaskContext);

            OutputCommitter taskOc = oupy.getOutputCommitter(wtaskContext);
            taskOc.setupTask(wtaskContext);
            RecordWriter<WritableComparable<?>, HCatRecord> rw = oupy.getRecordWriter(wtaskContext);

            while(rr.nextKeyValue()){
                rw.write(rr.getCurrentKey(), rr.getCurrentValue());
            }
            rw.close(wtaskContext);
            taskOc.commitTask(wtaskContext);
            rr.close();
        }

        oc.commitJob(ojob);
    }

    private TaskAttemptContext createTaskAttemptContext(Configuration tconf) {
        Configuration conf = (tconf == null) ? (new Configuration()) : tconf;
        TaskAttemptID taskId = new TaskAttemptID();
        conf.setInt("mapred.task.partition", taskId.getId());
        conf.set("mapred.task.id", "attempt__0000_r_000000_" + taskId.getId());
        TaskAttemptContext rtaskContext = HCatMapRedUtil.createTaskAttemptContext(conf , taskId);
        return rtaskContext;
    }


    public void testReadOrcAndRCFromPig() throws Exception {
        String tableSchema = "ti tinyint, si smallint,i int, bi bigint, f float, d double, b boolean";

        HcatTestUtils.createTestDataFile(TEXTFILE_LOCN,
            new String[]{
                "-3\0019001\00186400\0014294967297\00134.532\0012184239842983489.1231231234\001true"
                ,"0\0010\0010\0010\0010\0010\001false"
            }
        );

        // write this out to a file, and import it into hive
        createTable("inpy",tableSchema,null,"textfile");
        createTable("rc5318",tableSchema,null,"rcfile");
        createTable("orc5318",tableSchema,null,"orc");
        driverRun("LOAD DATA LOCAL INPATH '"+TEXTFILE_LOCN+"' OVERWRITE INTO TABLE inpy");

        // write it out from hive to an rcfile table, and to an orc table
//        driverRun("insert overwrite table rc5318 select * from inpy");
        copyTable("inpy","rc5318");
//        driverRun("insert overwrite table orc5318 select * from inpy");
        copyTable("inpy","orc5318");

        pigDump("inpy");
        pigDump("rc5318");
        pigDump("orc5318");

    }

}