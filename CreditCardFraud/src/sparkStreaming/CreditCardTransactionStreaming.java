package sparkStreaming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.StorageLevels;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

import sparkSQL.Account;
import sparkSQL.DataService;
import sparkSQL.Transaction;

/**
 * Use DataFrames and SQL to analysis Credit Card Transaction Fraud in UTF8
 * encoded, '\n' delimited text received from the network every second.
 *
 * Usage: CreditCardTransactionStreaming <hostname> <port> <hostname> and <port>
 * describe the TCP server that Spark Streaming transaction connect to receive
 * data.
 *
 * To run this on your local machine, you need to first run a Netcat server `$
 * nc -lk 9999` and then run the example `$ bin/run-example
 * Streamming.CreditCardTransactionStreaming localhost 9999`
 */
public final class CreditCardTransactionStreaming {
	private static final Pattern SPACE = Pattern.compile(",");

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err
					.println("Usage: CreditCardTransaction <hostname> <port>");
			System.exit(1);
		}

		// Create the context with a 1 second batch size
		SparkConf sparkConf = new SparkConf()
				.setAppName("CreditCardTransaction").setMaster("local[2]")
				.set("spark.executor.memory", "1g");

		@SuppressWarnings("resource")
		JavaStreamingContext ssc = new JavaStreamingContext(sparkConf,
				Durations.seconds(20));

		// Create a JavaReceiverInputDStream on target ip:port and count the
		// words in input stream of \n delimited text (eg. generated by 'nc')
		// Note that no duplication in storage level only for running locally.
		// Replication necessary in distributed scenario for fault tolerance.
		JavaReceiverInputDStream<String> lines = ssc.socketTextStream(args[0],
				Integer.parseInt(args[1]), StorageLevels.MEMORY_AND_DISK);

		JavaDStream<Transaction> transactions = lines
				.map(new Function<String, Transaction>() {
					private static final long serialVersionUID = 1L;

					@Override
					public Transaction call(String x) throws Exception {
						String[] splits = SPACE.split(x);
						if (splits.length < 6)
							return null;

						return new Transaction(Integer.parseInt(splits[0]),
								splits[1], splits[2], splits[3], Float
										.parseFloat(splits[4]), splits[5]);
					}
				});

		// Convert RDDs of the words DStream to DataFrame and run SQL query
		transactions.foreachRDD((rdd, time) -> {
			// Handle partitions empty
				if (rdd.partitions().isEmpty()) {
					return;
				}

				DataService instance = DataService.getInstance(rdd.context());
				TransactionFraudDetection pc = new TransactionFraudDetection();
				HashMap<String, Account> accountHash = new HashMap<>();
				HashMap<String, List<Transaction>> transHash = new HashMap<>();
				HashMap<Integer, Transaction> updateList = new HashMap<>();
				// Loop to each trans
				rdd.collect().forEach( (trans) -> {
					Account acc = null;
					List<Transaction> recentTrans = null;

					if (!accountHash.containsKey(trans.getAccountNo())) {
						// Get Account
						acc = instance.getAccount(trans.getAccountNo());
						accountHash.put(trans.getAccountNo(), acc);

						// Get List of most transaction
						recentTrans = instance.getRecentTransactions(trans.getAccountNo());
						transHash.put(trans.getAccountNo(),recentTrans);
					} else {
						acc = accountHash.get(trans.getAccountNo());
						recentTrans = transHash.get(trans.getAccountNo());
					}
					// Calculate alert
					String alert = pc.calcTotalPossibility(acc,trans, recentTrans);
					trans.setAlert(alert);
					// Add transaction in Hash
					updateList.put(trans.getTransactionId(),trans);
				});
				// Convert back to RDD and ready for saving
				JavaRDD<Transaction> updateAlerTrans = rdd.map(x -> {
					return updateList.get(x.getTransactionId());
				});

				// Insert transaction to Transaction table
				instance.insertNewTransaction(updateAlerTrans);
			});

		ssc.start();
		ssc.awaitTermination();
	}
}