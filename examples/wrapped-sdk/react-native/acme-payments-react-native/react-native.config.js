module.exports = {
  dependency: {
    platforms: {
      android: {
        packageImportPath: 'import com.acme.payments.reactnative.AcmePaymentsPackage;',
        packageInstance: 'new AcmePaymentsPackage()',
      },
      ios: null,
    },
  },
};
