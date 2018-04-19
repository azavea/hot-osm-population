const AWS = require('aws-sdk');

module.exports.trigger = function () {
    const jobDefinition = process.env.JOB_DEFINITION;
    const jobQueue = process.env.JOB_QUEUE;
    const jobName = process.env.JOB_NAME;
    const batch = new AWS.Batch({apiVersion: '2016-08-10'});
    const params = {
        'jobDefinition': jobDefinition,
        'jobQueue': jobQueue,
        'jobName': jobName
    };
  
    console.log('params', JSON.stringify(params));
    batch.submitJob(params, (err, res) => {
      if (err) {
        console.error(err);
      }
      console.log(`Job ${res.jobName} launched with id ${res.jobId}`);
    });
}