/*
 * sparse-probe: can a GPU-side gather replace reims' CPU views of guest RAM on this phone? (APK-PLAN §6.19)
 * Turnip on the Adreno 830: the sparse block size of a buffer (its memory alignment with the sparse flags),
 * whether host memory imports (VK_EXT_external_memory_host) can back sparse binds, and whether 16 KiB pieces
 * at scattered offsets of one import bind and read back right (iOS's GPU pages are 16 KiB, all scattered).
 * Build in Termux: clang -O2 -o sparse-probe sparse-probe.c -lvulkan
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <vulkan/vulkan.h>

#define CHECK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { printf("%s: %d\n", #x, r_); return 1; } } while (0)

int main(void)
{
    VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .apiVersion = VK_API_VERSION_1_3 };
    VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app };
    VkInstance inst;
    CHECK(vkCreateInstance(&ici, NULL, &inst));
    uint32_t n = 1;
    VkPhysicalDevice pd;
    vkEnumeratePhysicalDevices(inst, &n, &pd);
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(pd, &props);
    VkPhysicalDeviceFeatures feat;
    vkGetPhysicalDeviceFeatures(pd, &feat);
    printf("%s: sparseBinding %u residencyBuffer %u aliased %u, bufferImageGranularity %llu\n", props.deviceName,
           feat.sparseBinding, feat.sparseResidencyBuffer, feat.sparseResidencyAliased,
           (unsigned long long)props.limits.bufferImageGranularity);
    uint32_t nq = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, NULL);
    VkQueueFamilyProperties qf[8];
    if (nq > 8) nq = 8;
    vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qf);
    int fam = -1;
    for (uint32_t i = 0; i < nq; i++) {
        printf("queue family %u: flags %#x count %u\n", i, qf[i].queueFlags, qf[i].queueCount);
        if (fam < 0 && (qf[i].queueFlags & VK_QUEUE_SPARSE_BINDING_BIT) && (qf[i].queueFlags & VK_QUEUE_TRANSFER_BIT)) fam = i;
    }
    if (fam < 0) { printf("no sparse queue\n"); return 1; }
    float prio = 1;
    VkDeviceQueueCreateInfo qci = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex = fam,
                                    .queueCount = 1, .pQueuePriorities = &prio };
    const char *ext[] = { "VK_EXT_external_memory_host", "VK_KHR_external_memory" };
    VkPhysicalDeviceFeatures en = { .sparseBinding = VK_TRUE, .sparseResidencyBuffer = VK_TRUE, .sparseResidencyAliased = VK_TRUE };
    VkDeviceCreateInfo dci = { .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1,
                               .pQueueCreateInfos = &qci, .enabledExtensionCount = 2, .ppEnabledExtensionNames = ext,
                               .pEnabledFeatures = &en };
    VkDevice dev;
    CHECK(vkCreateDevice(pd, &dci, NULL, &dev));
    VkQueue q;
    vkGetDeviceQueue(dev, fam, 0, &q);

    /* the sparse block size: a sparse buffer's alignment */
    for (int residency = 0; residency < 2; residency++) {
        VkBufferCreateInfo bci = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = 4 << 20,
                                   .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                                            | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                   .flags = VK_BUFFER_CREATE_SPARSE_BINDING_BIT
                                            | (residency ? VK_BUFFER_CREATE_SPARSE_RESIDENCY_BIT
                                                           | VK_BUFFER_CREATE_SPARSE_ALIASED_BIT : 0) };
        VkBuffer b;
        CHECK(vkCreateBuffer(dev, &bci, NULL, &b));
        VkMemoryRequirements mr;
        vkGetBufferMemoryRequirements(dev, b, &mr);
        printf("sparse buffer%s: size %llu alignment (block) %llu, memory types %#x\n",
               residency ? " (residency, aliased)" : "", (unsigned long long)mr.size,
               (unsigned long long)mr.alignment, mr.memoryTypeBits);
        vkDestroyBuffer(dev, b, NULL);
    }

    /* one host import of 64 MiB (as guest RAM would be), 16 KiB pieces of it bound scattered into a sparse buffer */
    PFN_vkGetMemoryHostPointerPropertiesEXT hp = (PFN_vkGetMemoryHostPointerPropertiesEXT)
        vkGetDeviceProcAddr(dev, "vkGetMemoryHostPointerPropertiesEXT");
    size_t big = 64 << 20;
    uint8_t *host = mmap(NULL, big, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    for (size_t i = 0; i < big; i += 4) {
        *(uint32_t *)(host + i) = (uint32_t)(i / 16384);     /* each 16 KiB piece holds its own number */
    }
    VkMemoryHostPointerPropertiesEXT hpp = { .sType = VK_STRUCTURE_TYPE_MEMORY_HOST_POINTER_PROPERTIES_EXT };
    CHECK(hp(dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT, host, &hpp));
    printf("host import: memory types %#x\n", hpp.memoryTypeBits);
    VkBufferCreateInfo bci = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = 1 << 20,
                               .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                               .flags = VK_BUFFER_CREATE_SPARSE_BINDING_BIT | VK_BUFFER_CREATE_SPARSE_RESIDENCY_BIT };
    VkBuffer sb;
    CHECK(vkCreateBuffer(dev, &bci, NULL, &sb));
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(dev, sb, &mr);
    uint32_t type = 0;
    while (type < 32 && !((hpp.memoryTypeBits & mr.memoryTypeBits) & (1u << type))) type++;
    if (type == 32) { printf("no memory type both a sparse buffer and a host import take (sparse %#x, host %#x)\n",
                             mr.memoryTypeBits, hpp.memoryTypeBits); return 0; }
    VkImportMemoryHostPointerInfoEXT imp = { .sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_HOST_POINTER_INFO_EXT,
                                             .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT,
                                             .pHostPointer = host };
    VkMemoryAllocateInfo mai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &imp,
                                 .allocationSize = big, .memoryTypeIndex = type };
    VkDeviceMemory mem;
    CHECK(vkAllocateMemory(dev, &mai, NULL, &mem));
    uint64_t blk = mr.alignment;
    uint32_t pieces = (1 << 20) / blk;
    VkSparseMemoryBind binds[256];
    for (uint32_t i = 0; i < pieces && i < 256; i++) {
        /* piece i of the buffer from piece (37 * i + 11) % 4096 of the import: scattered, out of order */
        binds[i] = (VkSparseMemoryBind){ .resourceOffset = i * blk, .size = blk, .memory = mem,
                                         .memoryOffset = ((37ull * i + 11) % (big / blk)) * blk };
    }
    VkSparseBufferMemoryBindInfo bbi = { .buffer = sb, .bindCount = pieces, .pBinds = binds };
    VkBindSparseInfo bsi = { .sType = VK_STRUCTURE_TYPE_BIND_SPARSE_INFO, .bufferBindCount = 1, .pBufferBinds = &bbi };
    VkFenceCreateInfo fci = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    VkFence f;
    vkCreateFence(dev, &fci, NULL, &f);
    VkResult br = vkQueueBindSparse(q, 1, &bsi, f);
    printf("bind %u pieces of %llu bytes, scattered: %d\n", pieces, (unsigned long long)blk, br);
    if (br == VK_SUCCESS) {
        vkWaitForFences(dev, 1, &f, VK_TRUE, 5000000000ull);
        /* copy the gathered buffer into a plain one and check each piece's number */
        VkBufferCreateInfo dci2 = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = 1 << 20,
                                    .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT };
        VkBuffer db;
        CHECK(vkCreateBuffer(dev, &dci2, NULL, &db));
        VkMemoryRequirements dr;
        vkGetBufferMemoryRequirements(dev, db, &dr);
        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(pd, &mp);
        uint32_t ht = 0;
        while (ht < mp.memoryTypeCount && !((dr.memoryTypeBits & (1u << ht)) &&
               (mp.memoryTypes[ht].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
               (mp.memoryTypes[ht].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))) ht++;
        VkMemoryAllocateInfo dai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = dr.size,
                                     .memoryTypeIndex = ht };
        VkDeviceMemory dm;
        CHECK(vkAllocateMemory(dev, &dai, NULL, &dm));
        CHECK(vkBindBufferMemory(dev, db, dm, 0));
        VkCommandPoolCreateInfo cpi = { .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = fam };
        VkCommandPool cp;
        CHECK(vkCreateCommandPool(dev, &cpi, NULL, &cp));
        VkCommandBufferAllocateInfo cai = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = cp,
                                            .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1 };
        VkCommandBuffer cb;
        CHECK(vkAllocateCommandBuffers(dev, &cai, &cb));
        VkCommandBufferBeginInfo cbi = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
        vkBeginCommandBuffer(cb, &cbi);
        VkBufferCopy cp1 = { .size = 1 << 20 };
        vkCmdCopyBuffer(cb, sb, db, 1, &cp1);
        vkEndCommandBuffer(cb);
        VkSubmitInfo si = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cb };
        vkResetFences(dev, 1, &f);
        CHECK(vkQueueSubmit(q, 1, &si, f));
        vkWaitForFences(dev, 1, &f, VK_TRUE, 5000000000ull);
        uint8_t *out;
        CHECK(vkMapMemory(dev, dm, 0, 1 << 20, 0, (void **)&out));
        uint32_t bad = 0;
        for (uint32_t i = 0; i < pieces; i++) {
            uint32_t want = (uint32_t)((((37ull * i + 11) % (big / blk)) * blk) / 16384);
            uint32_t got = *(uint32_t *)(out + i * blk);
            if (got != want) {
                if (bad++ < 4) printf("piece %u: read %u, want %u\n", i, got, want);
            }
        }
        printf("gather through sparse binds: %u of %u pieces wrong\n", bad, pieces);
    }
    return 0;
}
